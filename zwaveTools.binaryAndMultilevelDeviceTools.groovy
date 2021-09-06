library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Events for Switches, Dimmers, Fans, Window Shades ",
        name: "binaryAndMultilevelDeviceTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.sendReceiveTools, zwaveTools.endpointTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/binaryAndMultiLevelDeviceTools.groovy"
)
////    Send Simple Z-Wave Commands to Device  ////	

void sendInitialCommand() {
	// If a device uses 'Supervision', then following a restart, code doesn't know the last sessionID that was sent to 
	// the device, so to reset that, send a command twice at startup.
	if (device.hasAttribute("switch") && (device.currentValue("switch") == "off")) {
		sendZwaveValue(value:0)
		sendZwaveValue(value:0)
	} else if ( device.hasAttribute("switch") && (device.currentValue("switch") == "on")) {
		if (device.hasAttribute("level")) { 
			sendZwaveValue(value:(device.currentValue("level") as Integer ))
			sendZwaveValue(value:(device.currentValue("level") as Integer ))
		} else {
			sendZwaveValue(value:99)
			sendZwaveValue(value:99)
		}
	}
}

void binaryAndMultiLevelDeviceTools_refresh() {
		if (record.classes.contains(0x25)) 		advancedZwaveSend(zwave.switchBinaryV1.switchBinaryGet(), ep)
		if (record.classes.contains(0x26)) 		advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelGet(), ep)
}

////    Send Simple Z-Wave Commands to Device  ////	
void sendZwaveValue(Map params = [value: null , duration: null , ep: null ] )
{
	Integer newValue = Math.max(Math.min(params.value, 99),0)
	List<Integer> supportedClasses = getThisEndpointClasses((Integer) params.ep)

	if (supportedClasses.contains(0x26)) { // Multilevel  type device
		if (! params.duration.is( null ) ) advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelSet(value: newValue, dimmingDuration:params.duration), params.ep)	
			else advancedZwaveSend(zwave.switchMultilevelV1.switchMultilevelSet(value: newValue), params.ep)
	} else if (supportedClasses.contains(0x25)) { // Switch Binary Type device
		advancedZwaveSend(zwave.switchBinaryV1.switchBinarySet(switchValue: newValue ), params.ep)
	} else if (supportedClasses.contains(0x20)) { // Basic Set Type device
		log.warn "Device ${targetDevice.displayName}: Using Basic Set to turn on device. A more specific command class should be used!"
		advancedZwaveSend(zwave.basicV1.basicSet(value: newValue ), params.ep)
	} else {
		log.error "Device ${device.displayName}: Error in function sendZwaveValue(). Device does not implement a supported class to control the device!.${ep ? " Endpoint # is: ${params.ep}." : ""}"
		return
	}
}

void zwaveEvent(hubitat.zwave.commands.switchbinaryv2.SwitchBinaryReport cmd, Integer ep = null )
{
	Map switchEvent
	if (cmd.value > 0) {
			switchEvent = [name: "switch", value: "on", descriptionText: "Switch turned on.", type: "physical"]
	} else {
			switchEvent = [name: "switch", value: "off", descriptionText: "Switch turned off", type: "physical"]
	}
	sendEventToEndpoints(event: switchEvent, ep:ep)
}

List<Map> getAllMultiLevelEvents(com.hubitat.app.DeviceWrapper cd, Integer targetLevel){
	List<Map> rvalue

	if (targetLevel == 0) {
		rvalue = [
				[name: "position", value:0 , unit: "%", descriptionText: "Position set to 0%", type: "physical"],
				[name: "windowShade", value: "closed" , descriptionText: "Window Shade closed", type: "physical" ],
				[name: "switch", value: "off", descriptionText: "Switch turned off", type: "physical" ],
				[name: "speed", value: "off", descriptionText: "Fan turned off", type: "physical" ],
			]
		if (! cd.hasAttribute("switch")) { rvalue.add([name: "level", value: 0, descriptionText: "Level set to 0%", type: "physical" ]) }
	} else {
			String shadePosition = (targetLevel == 100) ? "open" : "partially open"
			String fanSpeed = levelToSpeed(targetLevel)

		rvalue = [
				[name: "position", value:targetLevel , unit: "%", descriptionText: "Position set to ${targetLevel}%", type: "physical"],
				[name: "windowShade", value: shadePosition , descriptionText: "Window Shade ${shadePosition}", type: "physical"],
				[name: "switch", value: "on", descriptionText: "Switch turned on", type: "physical" ],
				[name: "speed", value: fanSpeed, descriptionText: "Fan level set to ${fanSpeed}", type: "physical" ],
				[name: "level", value: targetLevel, descriptionText: "Level set to ${targetLevel}%", type: "physical" ]	,
			]
	}
	return rvalue

}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, Integer ep = null ) { processSwitchReport(cmd, ep) }
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd, Integer ep = null ) { processSwitchReport(cmd, ep) }
void processSwitchReport(cmd, Integer ep)
{
	List<Map> events = []

	Integer targetLevel = cmd.value
	if (cmd.hasProperty("targetValue") && (!cmd.targetValue.is( null )) && ((Integer)(cmd.duration) > 0) ) targetLevel = cmd.targetValue as Integer
	if (targetLevel == 99) {targetLevel = 100 }
	
	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(ep ?: 0)
	
	targetDevices?.each{ targetDevice ->
			events = getAllMultiLevelEvents(targetDevice, targetLevel)	
			targetDevice?.parse(events)
		}
	if ( (ep ?: 0)  == 0) { 
		events = getAllMultiLevelEvents(device, targetLevel)
		this.parse(events)
		}	
}

void componentOn(com.hubitat.app.DeviceWrapper cd){ on(cd:cd) }

void on(Map params = [cd: null , duration: null , level: null ])
{
	Integer ep = getChildEndpointNumber(params.cd)
	
	sendEventToEndpoints(event:[name: "switch", value: "on", descriptionText: "Device turned on", type: "digital"] , ep:ep)

	Integer targetLevel = 100
	if (params.level) {
		targetLevel = (params.level as Integer) 
	} else {
		List<com.hubitat.app.DeviceWrapper> targets = getChildDeviceListByEndpoint(ep)
		if (ep == 0) targets += device
		targetLevel = (targets?.find{it.hasAttribute("level")}?.currentValue("level") as Integer) ?: 100
	}
	targetLevel = Math.min(Math.max(targetLevel, 1), 100)
			
	sendEventToEndpoints(event:[name: "level", value: targetLevel, descriptionText: "Device level set to ${targetLevel}%.", unit:"%", type: "digital"], ep:ep)
				
	sendZwaveValue(value: targetLevel, duration: params.duration, ep: ep)
}

void componentOff(com.hubitat.app.DeviceWrapper cd){ 	off(cd:cd) }

void off(Map params = [cd: null , duration: null ]) {
	Integer ep = getChildEndpointNumber(params.cd) // Returns 0 if cd == null 
	sendEventToEndpoints(event:[name: "switch", value: "off", descriptionText: "Device turned off", type: "digital"], ep:ep)
	sendZwaveValue(value: 0, duration: params.duration, ep: ep)
}

void componentSetLevel(com.hubitat.app.DeviceWrapper cd, level, transitionTime = null ) {
	if (cd.hasCapability("FanControl") ) {
			setSpeed(cd:cd, level:level, speed:levelToSpeed((Integer) level ))
		} else { 
			setLevel(level:level, duration:transitionTime, cd:cd) 
		}
}

void setLevel(level, duration = null ) {
	setLevel(level:level, duration:duration)
}
	
void setLevel(Map params = [cd: null , level: null , duration: null ])
{
	if ( (params.level as Integer) <= 0) {
		off(cd:params.cd, duration:params.duration)
	} else {
		on(cd:params.cd, level:params.level, duration:params.duration)
	}
}


void componentStartLevelChange(com.hubitat.app.DeviceWrapper cd, direction) { startLevelChange(direction, cd) }
void startLevelChange(direction, cd = null ){
	com.hubitat.app.DeviceWrapper targetDevice = (cd ? cd : device)
	Integer ep = cd ? (cd.deviceNetworkId.split("-ep")[-1] as Integer) : null
	
    Integer upDown = (direction == "down") ? 1 : 0
	
	def sendMe = zwave.switchMultilevelV1.switchMultilevelStartLevelChange(upDown: upDown, ignoreStartLevel: 1, startLevel: 0)
	
    advancedZwaveSend(sendMe, ep)
}



void componentStopLevelChange(com.hubitat.app.DeviceWrapper cd) { stopLevelChange(cd) }
void stopLevelChange(cd = null ){
	com.hubitat.app.DeviceWrapper targetDevice = (cd ? cd : device)
	Integer ep = cd ? (cd.deviceNetworkId.split("-ep")[-1] as Integer) : null
	
	advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelStopLevelChange(), ep)
	advancedZwaveSend(zwave.basicV1.basicGet(), ep)
}

////////////////////////////////////////////////////////
//////                Handle Fans                ///////
////////////////////////////////////////////////////////

String levelToSpeed(Integer level)
{
// 	Map speeds = [(0..0):"off", (1..20):"low", (21..40):"medium-low", (41-60):"medium", (61..80):"medium-high", (81..100):"high"]
//	return (speeds.find{ key, value -> key.contains(level) }).value
	switch (level)
	{
	case 0 : 		return "off" ; break
	case 1..20: 	return "low" ; break
	case 21..40: 	return "medium-low" ; break
	case 41..60: 	return "medium" ; break
	case 61..80: 	return "medium-high" ; break
	case 81..100: 	return "high" ; break
	default : return null
	}
}

Integer speedToLevel(String speed) {
	return ["off": 0, "low":20, "medium-low":40, "medium":60, "medium-high":80, "high":100].get(speed)
}

void componentSetSpeed(com.hubitat.app.DeviceWrapper cd, speed) { 
	Integer targetLevel = speedToLevel(speed)
	setLevel(level:targetLevel, cd:cd )
}
void setSpeed(speed) { setSpeed(speed:speed) }
void setSpeed(Map params = [speed: null , cd: null ])
{
	Integer targetLevel = speedToLevel(params.speed)
	setLevel(level:targetLevel, cd:(params.cd) )
}


/*
void componentSetSpeed(com.hubitat.app.DeviceWrapper cd, speed) { setSpeed(speed:speed, cd:cd) }
void setSpeed(speed, com.hubitat.app.DeviceWrapper cd = null ) { setSpeed(speed:speed, cd:cd) }
void setSpeed(Map params = [speed: null , level: null , cd: null ])
{
	com.hubitat.app.DeviceWrapper originatingDevice = params.cd ?: device
	if (params.speed.is( null ) ) {
		log.error "Device ${originatingDevice.displayName}: setSpeed command received without a valid speed setting. Speed setting was ${params.speed}. Returning without doing anything!"
		return
	}	
	
	Integer ep = params.cd ? (originatingDevice.deviceNetworkId.split("-ep")[-1] as Integer) : 0
	
	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(ep)
	

	
    if (logEnable) log.info "Device ${device.displayName}: received setSpeed(${params.speed}) request from child ${targetDevice.displayName}"

	String currentOnState = originatingDevice.currentValue("switch")
	Integer currentLevel = originatingDevice.currentValue("level") // Null if attribute isn't supported.
	Integer targetLevel
	
	if (params.speed == "on")
	{
		currentLevel = currentLevel ?: 100 // If it was a a level of 0, turn to 100%. Level should never be 0 -- except it might be 0 or null on first startup!
		
		targetDevices.each{
			it.sendEvent(name: "switch", value: "on", descriptionText: "Fan turned on", type: "digital")
			it.sendEvent(name: "level", value: currentLevel, descriptionText: "Fan level set", unit:"%", type: "digital")
			it.sendEvent(name: "speed", value: levelToSpeed(currentLevel), descriptionText: "Fan speed set", type: "digital")
		}
		sendZwaveValue(value: currentLevel, duration: 0, ep: ep)

	} else if (params.speed == "off")
	{ 
		targetDevices.each{
			it.sendEvent(name: "switch", value: "off", descriptionText: "Fan switched off", type: "digital")
			it.sendEvent(name: "speed", value: "off", descriptionText: "Fan speed set", type: "digital")
		}			
	
		sendZwaveValue(value: 0, duration: 0, ep: ep)
		
	} else {
		targetLevel = (params.level as Integer) ?: speedToLevel(params.speed) ?: currentLevel
		targetDevices.each{
			it.sendEvent(name: "switch", value: "on", descriptionText: "Fan turned on", type: "digital")
			it.sendEvent(name: "speed", value: params.speed, descriptionText: "Fan speed set", type: "digital")
			it.sendEvent(name: "level", value: targetLevel, descriptionText: "Fan level set", unit:"%", type: "digital")
		}
		sendZwaveValue(value: targetLevel, duration: 0, ep: ep)
	}
}
*/