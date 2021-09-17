
metadata {
    definition(name: "Generic Component Metering Central Scene Dimmer", namespace: "jvm", author: "jvm", component: true) {
        capability "Switch"
        capability "Refresh"
        capability "Actuator"
		capability "SwitchLevel"
		
		capability "EnergyMeter"
        capability "PowerMeter"
		capability "VoltageMeasurement"
        capability "CurrentMeter"
		attribute "energyConsumed", "number" 	// Custom Attribute for meter devices supporting energy consumption. Comment out if not wanted.	
		attribute "push", "number"
		attribute "held", "number"
		attribute "released", "number"
		attribute "doubleTapped", "number"
		attribute "multiTapButton", "number"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void updated() {
    log.info "Updated..."
    log.warn "description logging is: ${txtEnable == true}"
}

void installed() {
    log.info "Installed..."
    device.updateSetting("txtEnable",[type:"bool",value:true])
    refresh()
}

void parse(String description) { log.warn "parse(String description) not implemented" }

void parse(List description) {
    description.each {
        if (it.name in ["switch", "level", "power", "energy", "voltage", "energyConsumed", "amperage", "push",  "held", "released", "doubleTapped", "multiTapButton"]) {
            if (txtEnable) log.info it.descriptionText
            sendEvent(it)
        }
    }
}

void on() {
    parent?.componentOn(this.device)
}

void off() {
    parent?.componentOff(this.device)
}


void setLevel(level) {
    parent?.componentSetLevel(this.device,level)
}

void setLevel(level, ramp) {
    parent?.componentSetLevel(this.device,level,ramp)
}

void startLevelChange(direction) {
    parent?.componentStartLevelChange(this.device,direction)
}

void stopLevelChange() {
    parent?.componentStopLevelChange(this.device)
}

void refresh() {
    parent?.componentRefresh(this.device)
}
