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
    public int designMah = 0;
    public int fullMah = 0;
    public int rmMah = 0;
    public int cycles = 0;
    // hook 进程读不到引擎配置（cycle_fake），无数据时保持 -1，面板显示占位符。
    public int fakeCycles = -1;
    public int soh = 0;
    public int health = 0;
    public String proto = "";
}
