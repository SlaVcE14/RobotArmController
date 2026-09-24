/*
 * ============================================================
 *  Robot Arm Bluetooth Controller — Arduino Sketch
 * ============================================================
 *
 *  Controls 4 servo motors via Bluetooth (HC-05 / HC-06).
 *  Receives text commands from an Android app over Serial.
 *
 *  WIRING DIAGRAM
 *  ──────────────
 *
 *  Arduino Uno/Nano          HC-05 / HC-06
 *  ─────────────────         ─────────────
 *  5V  ──────────────────►  VCC
 *  GND ──────────────────►  GND
 *  TX (pin 1) ───────────►  RXD  (through voltage divider if HC module is 3.3V logic!)
 *  RX (pin 0) ◄───────────  TXD
 *
 *  Arduino Uno/Nano          Servos
 *  ─────────────────         ──────
 *  Pin 3  ───────────────►  Base Servo        (signal wire)
 *  Pin 5  ───────────────►  First Arm Servo   (signal wire)
 *  Pin 6  ───────────────►  Second Arm Servo  (signal wire)
 *  Pin 9  ───────────────►  Claw Servo        (signal wire)
 *
 *  All servo VCC ──► External 5V power supply (NOT Arduino 5V — servos draw too much current)
 *  All servo GND ──► Common GND with Arduino
 *
 *  PROTOCOL
 *  ────────
 *  Commands are single-line text terminated by newline (\n):
 *    B<+/->   — Set Base servo         (0–180)
 *    A<+/->   — Set First Arm servo    (0–180)
 *    S<+/->   — Set Second Arm servo   (0–180)
 *    C<O/C>   — Set Claw servo         (0–180)
 *    H        — Home (reset all to defaults)
 *    P        — Power the servo motors
 *
 *  Arduino responds with "OK\n" after each valid command.
 *
 * ============================================================
 */

#include <Servo.h>

// ─── Pin Configuration ───────────────────────────────────────
#define PIN_BASE      3
#define PIN_ARM1      5
#define PIN_ARM2      6
#define PIN_CLAW      9
#define PIN_RELAY     10

// ─── Default (Home) Angles ───────────────────────────────────
#define HOME_BASE     90
#define HOME_ARM1     90
#define HOME_ARM2     90
#define HOME_CLAW     160

// ─── Serial Baud Rate ────────────────────────────────────────
#define BAUD_RATE     9600

// ─── Speed Configuration ─────────────────────────────────────
// Delay in milliseconds per degree of movement (Higher = slower)
#define SERVO_SPEED_DELAY  15 

// ─── Servo Objects ───────────────────────────────────────────
Servo servoBase;
Servo servoArm1;
Servo servoArm2;
Servo servoClaw;

// ─── Target Angles ───────────────────────────────────────────
int targetBase  = HOME_BASE;
int targetArm1  = HOME_ARM1;
int targetArm2  = HOME_ARM2;
int targetClaw  = HOME_CLAW;

// ─── Current Angles ──────────────────────────────────────────
int currentBase  = HOME_BASE;
int currentArm1  = HOME_ARM1;
int currentArm2  = HOME_ARM2;
int currentClaw  = HOME_CLAW;

// ─── Input Buffer ────────────────────────────────────────────
String inputBuffer = "";
unsigned long lastMoveMillis = 0;

bool isPower = false;

void setup() {
  // Start serial communication (HC-05/HC-06/BLE default baud rate)
  Serial.begin(BAUD_RATE);

  // Set initial target and current to home
  targetBase = currentBase = HOME_BASE;
  targetArm1 = currentArm1 = HOME_ARM1;
  targetArm2 = currentArm2 = HOME_ARM2;
  targetClaw = currentClaw = HOME_CLAW;

  // Initialize servos to their home positions instantly on boot
  servoBase.write(currentBase);
  servoArm1.write(currentArm1);
  servoArm2.write(currentArm2);
  servoClaw.write(currentClaw);

  // Attach servos to pins
  servoBase.attach(PIN_BASE);
  servoArm1.attach(PIN_ARM1);
  servoArm2.attach(PIN_ARM2);
  servoClaw.attach(PIN_CLAW);

  pinMode(PIN_RELAY, OUTPUT);

  Serial.println("READY");
}

void loop() {
  // 1. Process incoming Bluetooth commands
  while (Serial.available() > 0) {
    char c = (char)Serial.read();

    if (c == '\n' || c == '\r') {
      inputBuffer.trim();
      if (inputBuffer.length() > 0) {
        processCommand(inputBuffer);
      }
      inputBuffer = "";
    } else {
      inputBuffer += c;
    }
  }

  // 2. Update servo positions smoothly
  unsigned long currentMillis = millis();
  if (currentMillis - lastMoveMillis >= SERVO_SPEED_DELAY) {
    lastMoveMillis = currentMillis;

    bool moved = false;

    // Move Base
    if (currentBase < targetBase) { currentBase++; moved = true; }
    else if (currentBase > targetBase) { currentBase--; moved = true; }
    if (moved) servoBase.write(currentBase);
    
    moved = false;
    
    // Move First Arm
    if (currentArm1 < targetArm1) { currentArm1++; moved = true; }
    else if (currentArm1 > targetArm1) { currentArm1--; moved = true; }
    if (moved) servoArm1.write(currentArm1);
    
    moved = false;

    // Move Second Arm
    if (currentArm2 < targetArm2) { currentArm2++; moved = true; }
    else if (currentArm2 > targetArm2) { currentArm2--; moved = true; }
    if (moved) servoArm2.write(currentArm2);
    
    moved = false;

    // Move Claw
    if (currentClaw < targetClaw) { currentClaw++; moved = true; }
    else if (currentClaw > targetClaw) { currentClaw--; moved = true; }
    if (moved) servoClaw.write(currentClaw);
  }
}

void processCommand(String cmd) {
  Serial.println(cmd);
  char servo = cmd.charAt(0);
  String value = cmd.substring(1);

  switch (servo) {
    case 'B':  // Base
    case 'b':
      if (value == "+" || value == "U") targetBase = constrain(targetBase + 4, 0, 180);
      else if (value == "-" || value == "D") targetBase = constrain(targetBase - 4, 0, 180);
      else targetBase = constrain(value.toInt(), 0, 180);
      Serial.println("OK");
      break;

    case 'A':  // First Arm
    case 'a':
      if (value == "+" || value == "U") targetArm1 = constrain(targetArm1 + 4, 40, 120);
      else if (value == "-" || value == "D") targetArm1 = constrain(targetArm1 - 4, 40, 120);
      else targetArm1 = constrain(value.toInt(), 40, 120);
      Serial.println("OK");
      break;

    case 'S':  // Second Arm
    case 's':
      if (value == "+" || value == "U") targetArm2 = constrain(targetArm2 + 4, 0, 180);
      else if (value == "-" || value == "D") targetArm2 = constrain(targetArm2 - 4, 0, 180);
      else targetArm2 = constrain(value.toInt(), 0, 180);
      Serial.println("OK");
      break;

    case 'C':  // Claw
    case 'c':
      if (value == "+" || value == "U") targetClaw = constrain(targetClaw + 4, 100, 180);
      else if (value == "-" || value == "D") targetClaw = constrain(targetClaw - 4, 100, 180);
      else targetClaw = constrain(value.toInt(), 160, 180);
      Serial.println("OK");
      break;

    case 'H':  // Home
    case 'h':
      goHome();
      Serial.println("OK");
      break;

    case 'P':
    case 'p':
      powerMotors();
      break;

    default:
      Serial.println("ERR");
      break;
  }
}

void goHome() {
  targetBase = HOME_BASE;
  targetArm1 = HOME_ARM1;
  targetArm2 = HOME_ARM2;
  targetClaw = HOME_CLAW;
}

void powerMotors(){
  if (!isPower){
    digitalWrite(PIN_RELAY, HIGH);
    isPower = true;
  }else {
    digitalWrite(PIN_RELAY, LOW);
    isPower = false;
  }
}
