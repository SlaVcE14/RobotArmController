# Robot Arm Controller

A complete software stack (Android App + Arduino Code) for controlling a 4-axis robotic arm over Bluetooth Low Energy (BLE). 

## Features
* **BLE Support**: Connects seamlessly to popular Bluetooth Low Energy serial modules (HM-10, AT-09, JDY-08, Nordic UART).

## Hardware Requirements
* **Arduino Board** (Uno, Nano, Mega, etc.)
* **BLE Module** (HM-10, AT-09, or equivalent BLE serial module)
* **4x Servo Motors** (e.g., SG90 or MG996R)
* **5V Power Supply** (Dedicated power for the servos—do not power them directly from the Arduino's 5V pin!)

## Wiring Guide

| Component            | Arduino Pin  | Notes                                                            |
|:---------------------|:-------------|:-----------------------------------------------------------------|
| **BLE Module (TX)**  | `RX` (Pin 0) |                                                                  |
| **BLE Module (RX)**  | `TX` (Pin 1) | Use a voltage divider if your BLE module is strictly 3.3V logic. |
| **Base Servo**       | `Pin 3`      |                                                                  |
| **First Arm Servo**  | `Pin 5`      |                                                                  |
| **Second Arm Servo** | `Pin 6`      |                                                                  |
| **Claw Servo**       | `Pin 9`      |                                                                  |
| **Relay for Power**  | `Pin 10`     | used for turing power for the servo motors                       |
| **All Servos VCC**   | External 5V  | **CRITICAL:** Connect to external power, not Arduino.            |
| **All Servos GND**   | GND          | Ensure common ground with Arduino and power supply.              |

## Getting Started

### 1. Arduino Setup
1. Open `arduino/robot_arm_bluetooth/robot_arm_bluetooth.ino` in the Arduino IDE.
2. Disconnect the BLE module's TX/RX wires (to prevent sketch upload errors).
3. Select your board and port, and click **Upload**.
4. Reconnect the BLE module.

### 2. Android App Setup
1. Open the project folder in **Android Studio**.
2. Sync the Gradle files.
3. Build and run the app on your physical Android device (Android 7.0+ recommended).
   *(Note: Bluetooth features cannot be tested on an emulator).*

## Bluetooth Protocol
The app communicates with the Arduino by sending simple text commands terminated by a newline (`\n`).

**Commands:**
* `B<+/->` - Move Base
* `A<+/->` - Move Arm 1
* `S<+/->` - Move Arm 2
* `C<0/180>` - Set Claw position
* (The Arduino automatically handles limits for these commands).*

**Action Commands:**
* `P` - Power the servo motors ()
* `H` - Send all servos to Home (90°) position.

## Customization
* **Servo Speed**: You can make the robotic arm move faster or slower by changing `#define SERVO_SPEED_DELAY 15` in the Arduino sketch. Higher numbers = slower movement.
* **Angle Limits**: You can adjust the `constrain()` limits in the Arduino `processCommand()` function to prevent the arm from hitting itself (e.g., Arm 1 is currently constrained between 40° and 120°).
