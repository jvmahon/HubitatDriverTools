metadata {
    definition(name: "Generic Component Enhanced Motion Sensor", namespace: "jvm", author: "jvm", component: true) {
        capability "Initialize"
		capability "Sensor"
        capability "Refresh"
        capability "Motion Sensor"
		capability "Battery"
		capability "TamperAlert"
		
		attribute "heartbeat", "string"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}


void parse(String description) { log.warn "parse(String description) not implemented" }

void parse(List description) {
    description.each {
        if (it.name in ["motion", "battery", "heartbeat", "tamper"]) {
            if (txtEnable) log.info it.descriptionText
            sendEvent(it)
        }
    }
}

void initialize() {
	if (txtEnable) log.info "Device ${device.displayName} initializing: Setting motion sensor value to 'inactive'."
	parse([[name:"motion", value:"inactive", descriptionText:"Initializing motion sensor to 'inactive'"]])
    refresh()
}

void refresh() {
    parent?.componentRefresh(this.device)
}
