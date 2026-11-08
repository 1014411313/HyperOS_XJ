package com.hyperos.xj.rear;

public final class ChargeState {
    public int level = 0;
    public double voltageV = 0;
    public double currentA = 0;
    public double powerW = 0;
    public double tempC = 0;
    public boolean charging = false;
    public boolean full = false;
    public String statusText = "未充电";
    public String stageText = "";
    public int etaMin = -1;
}
