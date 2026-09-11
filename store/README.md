This module implements an abstraction layer on top of Android's [Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider) 
and [Media Store](https://developer.android.com/training/data-storage/shared/media) allowing to access both of them using an unified API. Using this, the Voice 
Recorder app can store the recordings in the default location (using *MediaStore*) which should be suitable for most use cases but allows to change it to any 
directory on the external storage or even in `DocumentsProvider`s exposed by other apps (using *Storage Access Framework*) for more advanced uses cases (e.g., 
cloud storage). 
