# Sony cameras bluetooth protocol
This page is a compilation of all the information I was able to find on the web
with some of my own additions, based on analyzing the bluetooth traffic between the
phone and the camera

## Other sources of information
https://gethypoxic.com/blogs/technical/sony-camera-ble-control-protocol-di-remote-control  
https://gregleeds.com/reverse-engineering-sony-camera-bluetooth/  
https://github.com/whc2001/ILCE7M3ExternalGps/blob/main/PROTOCOL_EN.md (make sure to also checkout the issues page)


## Time Service (*UUID = 8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF*)

### Set time characteristic (UUID = 0xCC13)
Data array length is 13 bytes and all values are big endian

| Offset | Description      | Remark                                                      |
|--------|------------------|-------------------------------------------------------------|
| [0:2]  | Fixed value      | 0x0c0000                                                    |
| [3:4]  | UTC Year         |                                                             |
| [5]    | UTC Month        | January = 1                                                 |
| [6]    | UTC Day of month |                                                             |
| [7]    | UTC Hour         | 24H base                                                    |
| [8]    | UTC Minutes      |                                                             |
| [9]    | UTC Seconds      |                                                             |
| [10]   | DST              | Off=0x00 On=0x01                                            |
| [11]   | TZ Hours         | Positive TZ hours offset (eg. PST=UTC-8 → 24-8 = 16 = 0x10) |
| [12]   | TZ Minutes       | Timezone minutes offset from UTC                            |



### Set date format characteristic (UUID = 0xCC12)
Data value is a short with the following values:

| value | Description      |
|-------|------------------|
| 0x0001 | Y-D-M           |
| 0x0002 | D-M-Y           |
| 0x0003 | M-D-Y           |
| 0x0004 | M (English)-D-Y |



## Location Service (*UUID = 8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF*)
Before sending location information the Lock location and Enable location updates 
characteristics must be set with a value of 0x01.

### Lock location characteristic (UUID = 0xDD30)
Data value is a single byte:
0x00 - Off
0x01 - On


### Enable location updates characteristic (UUID = 0xDD31)
Data value is a single byte with value 0x01
0x00 - Off
0x01 - On

### Location data characteristic (UUID = 0xDD11)
Data is a 95 byte buffer

| Offset    | Description                                                  | 	Remark                                          |
|-----------|--------------------------------------------------------------|--------------------------------------------------|
| [0:1]     | Payload Length (exclude these two bytes)                     | 0x5D = 93 bytes                                  |
| [2:4]     | Fixed Data                                                   | 0x0802FC                                         |
| [5]       | Flag of transmitting timezone offset and DST offset required | 0x03 for transmit and 0x00 for do not transmit   |
| [6:10]    | Fixed Data                                                   | 0x0000101010                                     |
| [11:14]   | Latitude (multiplied by 10000000)                            | 0x0BF79E5E = 200777310 / 10000000 = 20.077731    |
| [15:18]   | Longitude (multiplied by 10000000) 	                         | 0x41C385A7 = 1103332775 / 10000000 = 110.3332775 |
| [19:20]   | 	UTC Year                                                    | 0x07E4 = 2020                                    |
| [21]      | 	UTC Month                                                   | 0x0B = 11                                        |
| [22]      | 	UTC Day                                                     | 0x05 = 5                                         |
| [23]      | 	UTC Hour                                                    | 0x04 = 4                                         |
| [24]      | 	UTC Minute                                                  | 0x02 = 2                                         |
| [25]      | 	UTC Second                                                  | 0x2A = 42                                        |
| [26:90]   | 	Zeros  	                                                    | 0x00                                             |
| *[91:92]  | 	Difference between UTC and current timezone in minutes 	    | 0x01E0 = 480min = 8h (UTC+8)                     | 
| *[93:94]  | 	Difference for DST in current timezone in minutes           |                                                  |

Note: Fields marked by * are required only when bit 2 of byte 4 of configuration data read is 1, otherwise omitted.

### Read configuration data characteristic (UUID = 0xDD21)
Data received is a 7 byte buffer

| Offset   | Description                                                                                                         | Remark                                                     |
|----------|---------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------|
| [0:3]    | Unknown                                                                                                             | 0x0610009C                                                 |
| [4]      | Some sort of flag (If bit 2 is 1 then timezone offset and DST offset must be provided when writing coordinate data) | 0x02 & 0x02 = 1, Timezone and DST offset data are required |
| [5:6]    | Unknown                                                                                                             | 0x0000                                                     |                                                         

* I didn't use this characteristic and it's not clear that it is useful



## Camera remote control service (*UUID = 8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF*)

### Remote command characteristic (UUID = 0xFF01)
The data for each command is as follows:

| Command         | Hex Code   |
|-----------------|------------|
| Focus Left      | 0x0105     |
| Focus Down      | 0x0107     |
| Focus Up        | 0x0106     |
| Shutter Down    | 0x0109     |
| Shutter Up      | 0x0108     |
| AutoFocus Down  | 0x0115     |
| AutoFocus Up    | 0x0114     |
| Zoom In Down    | 0x026d20   | 
| Zoom In Up      | 0x026c00   |
| Zoom Out Down   | 0x026b20   |
| Zoom Out Up 	   | 0x026a00   |
| C1 Down 	       | 0x0121     |
| C1 Up           | 0x0120     |
| Toggle Record   | 0x010e     |
| Focus In? Down  | 0x024720   |
| Focus In? Up    | 0x024600   |
| Focus Out? Down | 0x024520   |
| Focus Out? Up   | 0x024400   |

To reliably get the camera to take a picture, you'll want to first send the focus command, 
so the following 4 commands are needed in this order:

    0x0107
    0x0109
    0x0108
    0x0106


