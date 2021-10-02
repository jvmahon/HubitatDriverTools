
metadata {
    definition(name: "Generic Component Dimmer with level control", namespace: "jvm", author: "jvm", component: true) {
        capability "Switch"
        capability "Refresh"
        capability "Actuator"
		capability "SwitchLevel"
		
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "minDimLevel", type: "number", title: "Minimum Dimmer Level", defaultValue: 1, range:"1..100"
		
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
        if (hasAttribute (it.name)) {
            if (txtEnable) log.info it.descriptionText
			if ((it.name == "level") && (it.value < (minDimLevel as Integer))) {
			        if (txtEnable) log.info "Device ${device.displayName}: tried to set a level ${it.value} which is below the minimum allowed value for this device of ${minDimLevel}"

					parent?.componentSetLevel(this.device, (minDimLevel as Integer))
					it.value = minDimLevel
				}
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
	if (level < (minDimLevel as Integer)) {
		if (txtEnable) log.info "Device ${device.displayName}: tried to set a level ${level} which is below the minimum allowed value for this device of ${minDimLevel}. Setting to ${minDimLevel} instead."
		parent?.componentSetLevel(this.device, (minDimLevel as Integer))

	} else {
		parent?.componentSetLevel(this.device, level)
	}
}

void setLevel(level, ramp) {
	if (level < (minDimLevel as Integer)) {
		if (txtEnable) log.info "Device ${device.displayName}: tried to set a level ${level} which is below the minimum allowed value for this device of ${minDimLevel}. Setting to ${minDimLevel} instead."
		parent?.componentSetLevel(this.device, (minDimLevel as Integer), ramp)

	} else {
		parent?.componentSetLevel(this.device, level, ramp)
	}
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
