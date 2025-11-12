# Camera GPS Link

This Android app provide time and GPS synchronization for recent generations Sony cameras 
that require the Creator's App and don't work with the old Imaging Edge Mobile app.  

This app essentially does the same thing as Location Linking feature in Creator's App,
except it actually works reliably and doesn't randomly turn itself off. 
This issue has affected Creator's App since its first release and Sony are 
unable, or unwilling, to fix it.

### **This app is not associated with or endorsed by Sony**

## Features
* GPS synchronization, aka Location Linking
* Time synchronization - the camera will always be synchronized with the 
  Phone's time, time zone and daylight savings status
* Remote control - A simple shutter release remote control

## Privacy
The app doesn't collect any information so obviously it doesn't send anything to anyone.  
The GPS location is only used locally by the app to send to the camera, it is never collected or shared with anyone.  
The app doesn't connect to the Internet.

## Permissions
The app requires the following permission:  
**Precise Location** - Must be set to "When using the app" - required for providing location information to the camera  
**Bluetooth** - Required for pairing the camera with the app  
**Notifications** - Required for showing a persistent notification while the 
    app is running in the background, waiting to connect with the camera


## Usage
### Pairing the camera with the app
1. On the camera:
   * Go to MENU → Network → Bluetooth → Bluetooth Function - Set to "On"
   * Go to MENU → Network → Bluetooth → Pairing - The camera will enter pairing mode
2. In the app:
   * Press the "Scan for New Camera" button
   * The camera should be discovered like this:
   * Press the name of the camera 
3. On the the camera confirm the pairing request
4. On the phone confirm the pairing action
5. Once paired the app will continuously look for the camera and
   will automatically connect to it when it is near by

### Remote Control
1. Enable remote control on the camera:
    MENU → Network → Bluetooth → Bluetooth Rmt Ctrl - Set to "On"
2. On the app either press the "SHUTTER" button on the app's main screen or 
   directly from the notification which is displayed when the app is actively connected to the camera
**Note** that when remote control is enabled the auto power off feature of the camera is automatically 
  disabled and the camera will have to be manually turned off  


### Unpairing
Just press the "Disconnect" button for the app to disconnect and stop trying to
automatically connect to the camera.

### After phone restart
Due to the way location permissions work on Android the app is not 
able to start working after the phone is restarted without it being opened by the user.
Therefor, after a restart the app will display a notification reminding you
to open it, which will disappear once you do.


## License
MIT license
