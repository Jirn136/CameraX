CameraX is the application where user can use camera for video and image capturing.

To integrate the cameraX into the module

Please use the following steps :

1) use Intent to launch the application with required extras as below
   a) **Duration** -> Duration for video that need to be recorded.
   b) **Filepath** -> Custom FilePath for loading the files on the respective file directory.

2) Add interface callback(imageVideoCallbackListener) to the activity class that needed the result. From the result interface,
   we'll receive the path where the file stored.

3) We can have a callback (canCameraAccessListener) for user on call status to handle camera during the call (optional).
   a) Initialize the callback listener on the application/base class and obtain the Janus call status for handling the camera

//Duration that need to be recorded, at default it'll record 5 seconds
// FilePath that media has to be stored. User do not need to mention the filename and extension

#If user is on telephony call,
a broadcast has been received to notify the application whether the user receiving call or on call.

**Java:**
`Intent cameraIntent = new Intent(this,BaseViewPagerActivity.class);
cameraIntent.putExtra("MAX_REC_DURATION","duration");
cameraIntent.putExtra("FILEPATH","filePath");
startActivity(cameraIntent);`

**Kotlin:**
`val intent = Intent(this,BaseViewPagerActivity::class.java)
intent.apply {
putExtra("MAX_REC_DURATION","duration")
putExtra("FILEPATH","filePath")
}
startActivity(intent)`

From the callback, user will receive the uri of the path that stored the media.
