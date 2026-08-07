#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

constexpr uint8_t EN_LEFT = 4;
constexpr uint8_t IN_LEFT_1 = 2;
constexpr uint8_t IN_LEFT_2 = 3;
constexpr uint8_t EN_RIGHT = 7;
constexpr uint8_t IN_RIGHT_1 = 5;
constexpr uint8_t IN_RIGHT_2 = 6;
constexpr uint8_t SPEED_SLOW = 160;
constexpr uint8_t SPEED_MEDIUM = 180;
constexpr uint8_t SPEED_FAST = 255;
constexpr uint32_t SAFETY_TIMEOUT_MS = 1200;
constexpr char DEVICE_NAME[] = "OTIX";
constexpr char SERVICE_UUID[] = "7b183224-9168-443e-a927-7aeea07e8105";
constexpr char COMMAND_UUID[] = "7b183225-9168-443e-a927-7aeea07e8105";
constexpr char STATE_UUID[] = "7b183226-9168-443e-a927-7aeea07e8105";

BLEServer* bleServer = nullptr;
BLECharacteristic* stateCharacteristic = nullptr;
volatile bool deviceConnected = false;
bool previousConnectionState = false;
uint8_t currentSpeed = SPEED_MEDIUM;
char currentMovement = 'S';
uint32_t lastContactAt = 0;

void notifyState(const String& value) {
    if (!deviceConnected || stateCharacteristic == nullptr) return;
    stateCharacteristic->setValue(value);
    stateCharacteristic->notify();
}

void setSingleMotor(uint8_t enablePin, uint8_t input1, uint8_t input2, int value) {
    int duty = constrain(abs(value), 0, 255);
    if (value > 0) {
        digitalWrite(input1, HIGH);
        digitalWrite(input2, LOW);
        analogWrite(enablePin, duty);
    } else if (value < 0) {
        digitalWrite(input1, LOW);
        digitalWrite(input2, HIGH);
        analogWrite(enablePin, duty);
    } else {
        analogWrite(enablePin, 0);
        digitalWrite(input1, LOW);
        digitalWrite(input2, LOW);
    }
}

void setMotors(int left, int right) {
    setSingleMotor(EN_LEFT, IN_LEFT_1, IN_LEFT_2, left);
    // La orientación física del motor derecho invierte su sentido lógico.
    setSingleMotor(EN_RIGHT, IN_RIGHT_1, IN_RIGHT_2, -right);
}

void stopMotors(bool notify = true) {
    currentMovement = 'S';
    setMotors(0, 0);
    if (notify) notifyState("MOVE:S");
}

void applyMovement(char command) {
    int speed = currentSpeed;
    int inner = max(70, speed * 45 / 100);
    switch (command) {
        case 'F':
            setMotors(speed, speed);
            break;
        case 'B':
            setMotors(-speed, -speed);
            break;
        case 'L':
            setMotors(-speed, speed);
            break;
        case 'R':
            setMotors(speed, -speed);
            break;
        case 'J':
            setMotors(inner, speed);
            break;
        case 'Q':
            setMotors(speed, inner);
            break;
        case 'M':
            setMotors(-speed, -inner);
            break;
        case 'H':
            setMotors(-inner, -speed);
            break;
        case 'S':
            stopMotors();
            return;
        default:
            return;
    }
    currentMovement = command;
    notifyState("MOVE:" + String(command));
}

void applySpeed(char command) {
    switch (command) {
        case 'V':
            currentSpeed = SPEED_SLOW;
            break;
        case 'W':
            currentSpeed = SPEED_MEDIUM;
            break;
        case 'X':
            currentSpeed = SPEED_FAST;
            break;
        default:
            return;
    }
    notifyState("SPEED:" + String(currentSpeed));
    if (currentMovement != 'S') applyMovement(currentMovement);
}

void processCommand(char command) {
    lastContactAt = millis();
    if (command == 'P') return;
    if (command == 'V' || command == 'W' || command == 'X') {
        applySpeed(command);
        return;
    }
    applyMovement(command);
}

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* server) override {
        deviceConnected = true;
        lastContactAt = millis();
        stopMotors(false);
        notifyState("READY");
    }

    void onDisconnect(BLEServer* server) override {
        deviceConnected = false;
        stopMotors(false);
    }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* characteristic) override {
        String value = characteristic->getValue();
        for (size_t index = 0; index < value.length(); index++) {
            processCommand(value[index]);
        }
    }
};

void setup() {
    pinMode(EN_LEFT, OUTPUT);
    pinMode(IN_LEFT_1, OUTPUT);
    pinMode(IN_LEFT_2, OUTPUT);
    pinMode(EN_RIGHT, OUTPUT);
    pinMode(IN_RIGHT_1, OUTPUT);
    pinMode(IN_RIGHT_2, OUTPUT);
    stopMotors(false);
    Serial.begin(115200);
    BLEDevice::init(DEVICE_NAME);
    bleServer = BLEDevice::createServer();
    bleServer->setCallbacks(new ServerCallbacks());
    BLEService* service = bleServer->createService(SERVICE_UUID);
    BLECharacteristic* commandCharacteristic = service->createCharacteristic(
        COMMAND_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    commandCharacteristic->setCallbacks(new CommandCallbacks());
    stateCharacteristic = service->createCharacteristic(
        STATE_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    stateCharacteristic->addDescriptor(new BLE2902());
    stateCharacteristic->setValue("READY");
    service->start();
    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->setScanResponse(true);
    BLEDevice::startAdvertising();
}

void loop() {
    if (deviceConnected && currentMovement != 'S' && millis() - lastContactAt > SAFETY_TIMEOUT_MS) {
        stopMotors(false);
        notifyState("TIMEOUT");
    }
    if (!deviceConnected && previousConnectionState) {
        delay(300);
        BLEDevice::startAdvertising();
        previousConnectionState = false;
    }
    if (deviceConnected && !previousConnectionState) {
        previousConnectionState = true;
    }
    delay(10);
}
