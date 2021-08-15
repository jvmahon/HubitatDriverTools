library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Events for Switches, Dimmers, Fans, Window Shades ",
        name: "binaryAndMultiLevelDeviceTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.hubTools",
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
	List<Integer> supportedClasses = getThisEndpointClasses(ep)

	if ( !(0..100).contains(params.value) ) {
	log.warn "Device ${}: in function sendZwaveValue() received a value ${params.value} that is out of range. Valid range 0..100. Using value of ${newValue}."
	}
	
	if (supportedClasses.contains(0x26)) { // Multilevel  type device
		if (! params.duration.is( null) ) advancedZwaveSend(zwave.switchMultilevelV4.switchMultilevelSet(value: newValue, dimmingDuration:params.duration), params.ep)	
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

void zwaveEvent(hubitat.zwave.commands.switchbinaryv2.SwitchBinaryReport cmd, ep = null )
{
	String newSwitchState = ((cmd.value > 0) ? "on" : "off")
	Map switchEvent = [name: "switch", value: newSwitchState, descriptionText: "Switch set", type: "physical"]
	
	List <com.hubitat.app.DeviceWrapper> targetDevices = getTargetDeviceListByEndPoint(ep)?.findAll{it -> it.hasAttribute("switch")}
	
	targetDevices.each { thisTarget -> 
		thisTarget.sendEvent(switchEvent)
		if (txtEnable) log.info "Device ${thisTarget.displayName} set to ${newSwitchState}."
	}
	
	if (targetDevices.is( null )) log.error "Device ${device.displayName}: received a Switch Binary Report for a device that does not have a switch attribute. Endpoint ${ep ?: 0}."
}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, ep = null) { processSwitchReport(cmd, ep) }
void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd, ep = null) { processSwitchReport(cmd, ep) }
void processSwitchReport(cmd, ep)
{
	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(ep)
	targetDevices.each{ targetDevice ->
		if (targetDevice.hasAttribute("position")) 
		{ 
			targetDevice.sendEvent( name: "position", value: (cmd.value == 99 ? 100 : cmd.value) , unit: "%", descriptionText: "Position set", type: "physical" )
		}
		if (targetDevice.hasAttribute("windowShade"))
		{
			String positionDescription
			switch (cmd.value as Integer)
			{
				case 0:  positionDescription = "closed" ; break
				case 99:  positionDescription = "open" ; break
				default : positionDescription = "partially open" ; break
			}
			targetDevice.sendEvent( name: "windowShade", value: positionDescription, descriptionText: "Window Shade position set.", type: "physical" )	
		}

		if (targetDevice.hasAttribute("level") || targetDevice.hasAttribute("switch") ) // Switch or a fan
		{
			Integer targetLevel = 0

			if (cmd.hasProperty("targetValue")) //  Consider duration and target, but only when both are present and in transition with duration > 0 
			{
				targetLevel = cmd.targetValue ?: cmd.value
			} else {
				targetLevel = cmd.value
			}

			String priorSwitchState = targetDevice.currentValue("switch")
			String newSwitchState = ((targetLevel != 0) ? "on" : "off")
			Integer priorLevel = targetDevice.currentValue("level")

			if ((targetLevel == 99) && (priorLevel == 100)) targetLevel = 100

			if (targetDevice.hasAttribute("switch"))
			{
				targetDevice.sendEvent(	name: "switch", value: newSwitchState, descriptionText: "Switch state set", type: "physical" )
				if (txtEnable) log.info "Device ${targetDevice.displayName} set to ${newSwitchState}."
			}
			if (targetDevice.hasAttribute("speed")) 
			{
				targetDevice.sendEvent( name: "speed", value: levelToSpeed(targetLevel), descriptionText: "Speed set", type: "physical" )
			}
			if (targetDevice.hasAttribute("level") && (targetLevel != 0 )) // Only handle on values 1-99 here. If device was turned off, that would be handle in the switch state block above.
			{
				targetDevice.sendEvent( name: "level", value: targetLevel, descriptionText: "Level set", unit:"%", type: "physical" )
				if (txtEnable) log.info "Device ${targetDevice.displayName} level set to ${targetLevel}%"		
			}
		}
	}
}

void componentOn(com.hubitat.app.DeviceWrapper cd){ on(cd:cd) }

void on(Map params = [cd: null , duration: null , level: null ])
{
	Integer ep = params.cd ? (params.cd.deviceNetworkId.split("-ep")[-1] as Integer) : 0
	
	List <com.hubitat.app.DeviceWrapper> targetDevices = getTargetDeviceListByEndPoint(ep)
	Integer targetLevel = null	
	targetDevices.eachWithIndex { thisTarget , index ->
		if (txtEnable) log.info "Device ${thisTarget.displayName}: Turning device to: On."
		

		if (thisTarget.hasAttribute("switch")) {	
			thisTarget.sendEvent(name: "switch", value: "on", descriptionText: "Device turned on", type: "digital")
		} else {
			log.error "Device ${thisTarget.displayName}: Error in function on(). Device does not have a switch attribute."
		}
		
		if (thisTarget.hasAttribute("level")) {
			// compute target level only once -- for the first 'level' devices, and to maintain sync, set all others to the same.
			if (targetLevel.is ( null )){
				targetLevel = params.level ?: (thisTarget.currentValue("level") as Integer) ?: 100
				targetLevel = Math.max(Math.min(targetLevel, 100), 0)
			}
			thisTarget.sendEvent(name: "level", value: targetLevel, descriptionText: "Device level set", unit:"%", type: "digital")
			if (txtEnable) log.info "Device ${thisTarget.displayName}: Setting level to: ${targetLevel}%."

		}
	}
	sendZwaveValue(value: targetLevel ?: 100, duration: params.duration, ep: ep)
}

void componentOff(com.hubitat.app.DeviceWrapper cd){ 	off(cd:cd) }
void off(Map params = [cd: null , duration: null ]) {
	Integer ep = params.cd ? (params.cd.deviceNetworkId.split("-ep")[-1] as Integer) : 0
	
	List <com.hubitat.app.DeviceWrapper> targetDevices = getTargetDeviceListByEndPoint(ep)
	
	targetDevices.each { thisTarget ->
	
		if (txtEnable) log.info "Device ${thisTarget.displayName}: Turning device to: Off."

		if (thisTarget.hasAttribute("switch")) {	
			thisTarget.sendEvent(name: "switch", value: "off", descriptionText: "Device turned off", type: "digital")
			
			sendZwaveValue(value: 0, duration: params.duration, ep: ep)
		} else {
			log.error "Device ${thisTarget.displayName}: Error in function off(). Device does not have a switch attribute."
		}
	}
}



void componentSetLevel(com.hubitat.app.DeviceWrapper cd, level, transitionTime = null) {
	if (cd.hasCapability("FanControl") ) {
			setSpeed(cd:cd, level:level, speed:levelToSpeed(level as Integer))
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

void componentSetSpeed(com.hubitat.app.DeviceWrapper cd, speed) { setSpeed(speed:speed, cd:cd) }
void setSpeed(speed, com.hubitat.app.DeviceWrapper cd = null ) { setSpeed(speed:speed, cd:cd) }
void setSpeed(Map params = [speed: null , level: null , cd: null ])
{
	com.hubitat.app.DeviceWrapper originatingDevice = params.cd ?: device
	Integer ep = params.cd ? (originatingDevice.deviceNetworkId.split("-ep")[-1] as Integer) : 0
	
	List<com.hubitat.app.DeviceWrapper> targetDevices = getTargetDeviceListByEndPoint(ep)
	
	if (params.speed.is( null ) ) {
		log.error "Device ${originatingDevice.displayName}: setSpeed command received without a valid speed setting. Speed setting was ${params.speed}. Returning without doing anything!"
		return
	}
	
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
