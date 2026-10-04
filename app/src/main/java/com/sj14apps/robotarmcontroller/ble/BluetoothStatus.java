package com.sj14apps.robotarmcontroller.ble;

public class BluetoothStatus {

    public Status status;
    public String message;

    public BluetoothStatus(Status status, String message){
        this.status = status;
        this.message = message;
    }

}

