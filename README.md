# Android bluetooth low energy KISS network bridge for the Radtel RT-950 Pro.

### Allows APRSDroid and other KISS apps that may connect to TNCs over a network to communicate with the RT-950 Pro.

Compiled self-signed APKs are available in [Releases](https://github.com/nicsure/RT950Pro-BTLE-TCP-Bridge/releases).


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

## Channel Editor

Once connected to your RT-950 Pro over Bluetooth, a button will appear  `Open Channel Manager`  
This will pause the TCP Bridge and allow you to manage the radio's channels in a similar way to the RMS.  
I would advise against using `Read All` and `Write All` as it will be very slow. Instead make use of the `Read Selected` and `Write Selected` buttons.
  
