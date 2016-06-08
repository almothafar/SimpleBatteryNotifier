package com.almothafar.simplebatterynotifier.model;

/**
 * Created by Al-Mothafar on 24/08/2015.
 */
public class BatteryDO {
    private int level;
    private int plugged;
    private int scale;
    private int status;
    private int temperature;
    private int voltage;
    private boolean present;
    private int capacity;
    private String technology;
    private String powerSource;
    private String health;
    private int intHealth;
    private boolean warningHealth;
    private boolean criticalHealth;


    public float getBatteryPercentage() {
        float batteryPercentage = (level / (float) scale) * 100;
        return batteryPercentage;
    }

    public String getHealth() {
        return health;
    }

    public BatteryDO setHealth(String health) {
        this.health = health;
        return this;
    }

    public int getLevel() {
        return level;
    }

    public BatteryDO setLevel(int level) {
        this.level = level;
        return this;
    }

    public int getPlugged() {
        return plugged;
    }

    public BatteryDO setPlugged(int plugged) {
        this.plugged = plugged;
        return this;
    }

    public boolean isPresent() {
        return present;
    }

    public BatteryDO setPresent(boolean present) {
        this.present = present;
        return this;
    }

    public int getScale() {
        return scale;
    }

    public BatteryDO setScale(int scale) {
        this.scale = scale;
        return this;
    }

    public int getStatus() {
        return status;
    }

    public BatteryDO setStatus(int status) {
        this.status = status;
        return this;
    }

    public String getTechnology() {
        return technology;
    }

    public BatteryDO setTechnology(String technology) {
        this.technology = technology;
        return this;
    }

    public int getTemperature() {
        return temperature;
    }

    public BatteryDO setTemperature(int temperature) {
        this.temperature = temperature;
        return this;
    }

    public int getVoltage() {
        return voltage;
    }

    public BatteryDO setVoltage(int voltage) {
        this.voltage = voltage;
        return this;
    }

    public int getCapacity() {
        return capacity;
    }

    public BatteryDO setCapacity(int capacity) {
        this.capacity = capacity;
        return this;
    }

    public String getPowerSource() {
        return powerSource;
    }

    public BatteryDO setPowerSource(String powerSource) {
        this.powerSource = powerSource;
        return this;
    }

    public boolean isCriticalHealth() {
        return criticalHealth;
    }

    public BatteryDO setCriticalHealth(boolean criticalHealth) {
        this.criticalHealth = criticalHealth;
        return this;
    }

    public boolean isWarningHealth() {
        return warningHealth;
    }

    public BatteryDO setWarningHealth(boolean warningHealth) {
        this.warningHealth = warningHealth;
        return this;
    }

    public int getIntHealth() {
        return intHealth;
    }

    public BatteryDO setIntHealth(int intHealth) {
        this.intHealth = intHealth;
        return this;
    }
}
