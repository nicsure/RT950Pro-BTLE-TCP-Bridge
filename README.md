# Android bluetooth low energy network bridge for the Radtel RT-950 Pro.

### Allows APRSDroid and other apps that may connect to TNCs over a network to communicate with the RT-950 Pro.

A compiled self-signed APK is available in [Releases](https://github.com/nicsure/RT950Pro-BTLE-TCP-Bridge/releases).


## To Use

1. Open this app on your Android device.
2. Select a port, you will likely leave this at 14580, which is the default port used by APRSDroid.
3. Make sure your RT-950 Pro is switched on, has Bluetooth enabled and is in KISS mode.
4. Tap "Scan for RT950Pro"
5. When you see "walkie-talkie" appear, tap it.
6. Wait for the "BLE Ready" message to appear at the top.
7. Leave the app running and switch back to the Home Screen on your Android device.
8. Open your APRS app on your Android device, such as APRSDroid.
9. Configure the APRS app to use 'KISS TNC' mode and TCP/IP connection. Ensure the port is the same as the one you selected in step 2. The network address should be entered as '127.0.0.1'
  
