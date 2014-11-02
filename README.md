flickr-tool
===========

A tiny utility tool to manipulate flickr in Scala.

Basically a thin Scala wrapper of [flickr4java](https://github.com/callmeal/Flickr4Java) and a CLI interface.

Usage
-----

Use [sbt](http://www.scala-sbt.org) to build and run.

    $ brew install sbt
    $ set "run -action ACTION -apiKey YOUR_FLICKR_API_KEY -apiSecret YOUR_FLICKR_API_SECRET"

You may be prompted to authorize access to your account.
Open displayed URL and accept it on the browser then copy and paste displayed code to complete authorization.
Next time, you can give ``-token YOUR_TOKEN -secret YOUR_SECRET`` options to skip this step.

There are next actions available.

* ``photos``

    Dump id, title and download url for the original photos in JSON format.

* ``photoSets``

    Dump id, title and ids of photos for the all photo sets.

* ``deleteAllPhotos``

    Delete all photos. __THIS ACTION ACTUALLY DELETE EVERYTHING__.

* ``deleteAllPhotoSets``

    Delete all photo sets. __THIS ACTION ACTUALLY DELETE EVERYTHING__.