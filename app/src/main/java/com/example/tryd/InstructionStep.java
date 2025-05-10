package com.example.tryd;

import org.osmdroid.util.GeoPoint;


public class InstructionStep {
    public GeoPoint point;
    public String instruction;
    public boolean announced = false;

    public InstructionStep(GeoPoint point, String instruction) {
        this.point = point;
        this.instruction = instruction;
    }
}
