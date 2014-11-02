package at.niw.flickrtool

import java.util.Date
import java.util.concurrent.{LinkedBlockingQueue, SynchronousQueue, ThreadPoolExecutor, TimeUnit}

import com.flickr4java.flickr.auth.Permission
import com.flickr4java.flickr.photos.{Extras, Photo}
import com.flickr4java.flickr.photosets.Photoset
import com.flickr4java.flickr.{Flickr, REST, RequestContext, SearchResultList}
import com.twitter.concurrent.NamedPoolThreadFactory
import com.twitter.util.{NoFuture, Await, Future, FuturePool}
import org.json4s.NoTypeHints
import org.json4s.native.Serialization
import org.scribe.model._

import scala.collection.JavaConverters._
import scala.io.StdIn
import scala.util.control.Exception._

case class ConsumerKey(key: String, secret: String)

case class AccessToken(token: String, secret: String)

object FlickrClient {
  def apply(consumerKey: ConsumerKey, accessToken: Option[AccessToken]) = new FlickrClient(consumerKey, accessToken)

  private val POOL_SIZE = 200

  private val pool = {
    val factory = new NamedPoolThreadFactory("flickr-tool", makeDaemons = true)
    val executor = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, 60, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable], factory)
    FuturePool(executor)
  }
}

class FlickrClient(consumerKey: ConsumerKey, accessToken: Option[AccessToken]) {
  import FlickrClient._

  private lazy val flickr = new Flickr(consumerKey.key, consumerKey.secret, new REST())

  private lazy val authorize = {
    val authInterface = flickr.getAuthInterface
    val requestToken = authInterface.getRequestToken

    println("Open the URL to authorize:")
    println(authInterface.getAuthorizationUrl(requestToken, Permission.DELETE))

    val verification = StdIn.readLine("Type code:")
    val accessToken = authInterface.getAccessToken(requestToken, new Verifier(verification))

    println("Use next options nexime")
    println(s"-token ${accessToken.getToken} -secret ${accessToken.getSecret}")

    accessToken
  }

  private lazy val token = accessToken match {
    case Some(AccessToken(token, secret)) => new Token(token, secret)
    case _ => authorize
  }

  private lazy val auth = flickr.getAuthInterface.checkToken(token)

  def userId = auth.getUser.getId

  // Horrible, flickr4java is using thread local context to pass the authentication context
  // Dispatch Function1 within the future pool and set thread local request context.
  private def withFlickr[T](f: (Flickr) => T) = pool {
    val context = RequestContext.getRequestContext
    val originalAuth = context.getAuth
    context.setAuth(auth)
    val result = f(flickr)
    context.setAuth(originalAuth)
    result
  }

  def allSearchResults[T, U](paginatedSearch: (Option[Int]) => Future[SearchResultList[T]])(f: T => Future[U]) = {
    paginatedSearch(Some(1)) flatMap { head =>
      val headList = Seq(Future(head))
      val tailList = (head.getPage + 1 to head.getPages).map { page =>
        paginatedSearch(Some(page))
      }
      Future.collect(headList ++ tailList).map { searchResultLists =>
        Future.collect {
          searchResultLists.map { searchResultList =>
            searchResultList.asScala.map(f)
          }.flatten
        }
      }.flatten
    }
  }

  def photos(maxUploadDate: Option[Date] = None)(page: Option[Int] = None) = withFlickr { flickr =>
    flickr.getPeopleInterface.getPhotos(userId, null, null, maxUploadDate.orNull, null, null, null, null, Extras.ALL_EXTRAS, 0, page.getOrElse(0))
  }

  def sizes(photoId: String) = withFlickr(_.getPhotosInterface.getSizes(photoId))

  def photoSets(page: Option[Int] = None) = withFlickr { flicker =>
    val photoSets = flickr.getPhotosetsInterface.getList(userId, page.getOrElse(0), 0)
    val searchResultList = new SearchResultList[Photoset]
    searchResultList.setPage(photoSets.getPage)
    searchResultList.setPages(photoSets.getPages)
    searchResultList.setPerPage(photoSets.getPerPage)
    searchResultList.setTotal(photoSets.getTotal)
    photoSets.getPhotosets.asScala foreach { photoSet =>
      searchResultList.add(photoSet)
    }
    searchResultList
  }

  def photosInPhotoSet(photoSetId: String)(page: Option[Int]) = withFlickr { flickr =>
    flickr.getPhotosetsInterface.getPhotos(photoSetId, Extras.ALL_EXTRAS, 0, 0, page.getOrElse(0))
  }

  def deletePhotoSet(photoSetId: String) = withFlickr { flickr =>
    allCatch.opt {
      flickr.getPhotosetsInterface.delete(photoSetId)
    }.isDefined
  }

  def deletePhoto(photoId: String) = withFlickr { flickr =>
    allCatch.opt {
      flickr.getPhotosInterface.delete(photoId)
    }.isDefined
  }
}

object Args {
  def apply(args: Array[String]) = {
    val argsMap = args.foldLeft(List[(String, List[String])]()) { (args, arg) =>
      if (arg.startsWith("-")) {
        val key = arg.dropWhile(_ == '-')
        (key -> Nil) :: args
      } else {
        (args.head._1 -> (arg :: args.head._2)) :: args
      }
    }.reverse.toMap

    new Args(argsMap)
  }
}

class Args(args: Map[String, List[String]]) {
  def apply(key: String) = args.get(key).flatMap(_.headOption)
}

object Tool {
  def apply(args: Args) = new Tool(args)
}

class Tool(args: Args) {
  private lazy val client = {
    val consumerKey = (args("apiKey"), args("apiSecret")) match {
      case (Some(key), Some(secret)) => ConsumerKey(key, secret)
      case _ => throw new RuntimeException("Missing -apiKey and/or -apiSecret")
    }

    val accessToken = (args("token"), args("secret")) match {
      case (Some(token), Some(secret)) => Some(AccessToken(token, secret))
      case _ => None
    }

    FlickrClient(consumerKey, accessToken)
  }

  private val now = new Date()

  private def photos = client.allSearchResults(client.photos(Some(now))) { photo =>
    def originalUrl(photo: Photo) = photo.getMedia match {
      case "video" =>
        client.sizes(photo.getId).map { sizes =>
          // This is a destructive operation
          photo.setSizes(sizes)
          photo.getVideoOriginalUrl
        }
      case _ => Future(photo.getOriginalUrl)
    }

    originalUrl(photo).map { originalUrl =>
      photo.getId -> Map("title" -> photo.getTitle, "url" -> originalUrl)
    }
  }.map(_.toMap)

  private def photoSets = client.allSearchResults(client.photoSets) { photoSet =>
    client.allSearchResults(client.photosInPhotoSet(photoSet.getId)) { photo =>
      Future(photo.getId)
    }.map { photoIds =>
      photoSet.getId -> Map("title" -> photoSet.getTitle, "photos" -> photoIds)
    }
  }.map(_.toMap)

  private def deleteAllPhotos = client.allSearchResults(client.photos(Some(now))) { photo =>
    Future(photo.getId)
  }.map { photoIds =>
    Future.collect {
      photoIds.map { photoId =>
        client.deletePhoto(photoId) map { result =>
          photoId -> result
        }
      }
    }.map(_.toMap)
  }.flatten

  private def deleteAllPhotoSets = client.allSearchResults(client.photoSets) { photoSet =>
    Future(photoSet.getId)
  }.map { photoSetIds =>
    Future.collect {
      photoSetIds.map { photoSetId =>
        client.deletePhotoSet(photoSetId) map { result =>
          photoSetId -> result
        }
      }
    }.map(_.toMap)
  }.flatten

  private implicit val jsonFormats = Serialization.formats(NoTypeHints)

  def run {
    val action = args("action") match {
      case Some("photos") => photos
      case Some("photoSets") => photoSets
      case Some("deleteAllPhotos") => deleteAllPhotos
      case Some("deleteAllPhotoSets") => deleteAllPhotoSets
      case _ => throw new RuntimeException("Missing -action")
    }
    println(Serialization.write(Await.result(action)))
  }
}

object Main {
  def main(args: Array[String]) {
    Tool(Args(args)).run
  }
}
