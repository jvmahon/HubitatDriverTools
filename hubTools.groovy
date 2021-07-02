library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handle Interactions with the Hubitat Hub",
        name: "hubTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "none",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/hubTools.groovy"
)



////    Z-Wave Message Parsing   ////
void parse(String description) {
		hubitat.zwave.Command cmd = zwave.parse(description, defaultParseMap)
		if (cmd) { zwaveEvent(cmd) }
}

////    Z-Wave Message Sending to Hub  ////
// void sendToDevice(List<hubitat.zwave.Command> cmds) { sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds), hubitat.device.Protocol.ZWAVE)) }

// void sendToDevice(hubitat.zwave.Command cmd, ep = null ) { sendHubCommand(new hubitat.device.HubAction(secure(cmd, ep), hubitat.device.Protocol.ZWAVE)) }

// void sendToDevice(String cmd) { sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE)) }

List<String> commands(List<hubitat.zwave.Command> cmds, Long delay=200) { return delayBetween(cmds.collect{ it }, delay) }

////    Security Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand( defaultParseMap )
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand) }
}

String secure(Integer cmd, Integer hexBytes = 2, ep = null ) { 
    return secure(hubitat.helper.HexUtils.integerToHexString(cmd, hexBytes), ep) 
}

String secure(String cmd, ep = null ){ 
	if (ep) {
		return zwaveSecureEncap(zwave.multiChannelV4.multiChannelCmdEncap(sourceEndPoint: 0, bitAddress: 0, res01:0, destinationEndPoint: ep).encapsulate(cmd))
	} else {
		return zwaveSecureEncap(cmd) 
	}
}

String secure(hubitat.zwave.Command cmd, ep = null ){ 
	if (ep) {
		return zwaveSecureEncap(zwave.multiChannelV4.multiChannelCmdEncap(sourceEndPoint: 0, bitAddress: 0, res01:0, destinationEndPoint: ep).encapsulate(cmd))
	} else {
		return zwaveSecureEncap(cmd) 
	}
}

////    Multi-Channel Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    hubitat.zwave.Command  encapsulatedCommand = cmd.encapsulatedCommand(defaultParseMap)
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint) }
}


// @Field static results in variable being shared among all devices that use the same driver, so I use a concurrentHashMap keyed by a device's deviceNetworkId to get a unqiue value for a particular device
// supervisionSessionIDs stores the last used sessionID value (0..63) for a device. It must be incremented mod 64 on each send
// supervisionSentCommands stores the last command sent
// Each is initialized for 64 devices, but can automatically grow
@Field static ConcurrentHashMap<String, Integer> supervisionSessionIDs = new ConcurrentHashMap<String, Integer>(64)
@Field static ConcurrentHashMap<String, ConcurrentHashMap> supervisionSentCommands = new ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>>(64)

Integer getNewSessionId() {
		// Get the next session ID mod 64, but if there is no stored session ID, initialize it with a random value.
		Integer lastSessionID = supervisionSessionIDs.get(device.getDeviceNetworkId() as String,((Math.random() * 64) % 64) as Integer )
		Integer nextSessionID = (lastSessionID + 1) % 64 // increment and then mod with 64, and then store it back in the Hash table.
		supervisionSessionIDs.replace(device.getDeviceNetworkId(), nextSessionID)
		return nextSessionID
} 

Boolean getNeverSupervise()
{
	Integer mfr = 			getDataValue("manufacturer")?.toInteger()
	Integer deviceId = 		getDataValue("deviceId")?.toInteger()
	Integer deviceType =	getDataValue("deviceType")?.toInteger()
	List<Map> supervisionBroken = [
			[	manufacturer:798,  	deviceType:14,	deviceId: 1  	], // Inovelli LZW36 firmware 1.36 supervision is broken!
			]

	Map thisDevice =	supervisionBroken.find{ element ->
						((element.manufacturer == mfr ) && (element.deviceId == deviceId ) && (element.deviceType == deviceType))
					}
	if (thisDevice && logEnable) log.warn "Device ${device.displayName}: This device is on the broken supervision list. Check manufacturer for a firmware update. Not supervising."
	return ( thisDevice ? true : false )
}

Boolean getSuperviseThis() {
		if (neverSupervise) return false
		return (getDataValue("S2")?.toInteger() != null )
}

void sendSupervised(hubitat.zwave.Command cmd, ep = null ) { 
	Integer thisSessionId = getNewSessionId()
	
	ConcurrentHashMap commandStorage = supervisionSentCommands.get(device.getDeviceNetworkId() as String, new ConcurrentHashMap<Integer, hubitat.zwave.Command>(64))
	
	def sendThisCommand = cmd // use def rather than more specific data class, as data class changes from a Hubitat Command to a String after security encapsulation

	if (superviseThis)
	{
		sendThisCommand = zwave.supervisionV1.supervisionGet(sessionID: thisSessionId, statusUpdates: true ).encapsulate(sendThisCommand)
		sendThisCommand = secure(sendThisCommand, ep) // Returns security and endpoint encapsulated string
		commandStorage.put(thisSessionId, sendThisCommand)
		sendHubCommand(new hubitat.device.HubAction( sendThisCommand, hubitat.device.Protocol.ZWAVE)) 
		runIn(3, supervisionCheck)	
	} else {
		sendThisCommand = secure(sendThisCommand, ep) // Returns security and endpoint encapsulated string
		sendHubCommand(new hubitat.device.HubAction( sendThisCommand, hubitat.device.Protocol.ZWAVE)) 
	}
}

void sendUnsupervised(hubitat.zwave.Command cmd, ep = null ) { 
	def sendThisCommand = secure(cmd, ep)
	sendHubCommand(new hubitat.device.HubAction( sendThisCommand, hubitat.device.Protocol.ZWAVE)) 
}
	

// This handles a supervised message (a "get") received from the Z-Wave device //
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, ep = null ) {
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand(defaultParseMap)
	
    if (encapsulatedCommand) {
		if ( ep ) {
			zwaveEvent(encapsulatedCommand, ep)
		} else {
			zwaveEvent(encapsulatedCommand)
		}
    }
	
	hubitat.zwave.Command confirmationReport = (new hubitat.zwave.commands.supervisionv1.SupervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0))
	
	sendHubCommand(new hubitat.device.HubAction(secure(confirmationReport, ep), hubitat.device.Protocol.ZWAVE))
}

Boolean ignoreSupervisionNoSupportCode()
{
	// Some devices implement the Supervision command class incorrectly and return a "No Support" code even when they work.
	// This function is to ignore the No Support code from those devices.
	List<Map> poorSupervisionSupport = [
			[	manufacturer:12,  	deviceType:17479,	deviceId: 12340  	], // HomeSeer WD100 S2 is buggy!
			[	manufacturer:12,  	deviceType:17479,	deviceId: 12342  	], // HomeSeer WD200 is buggy!
			]
    	Map thisDevice =	poorSupervisionSupport.find{ element ->
							((element.manufacturer == getDataValue("manufacturer")?.toInteger()) && (element.deviceId == getDataValue("deviceId")?.toInteger()) && (element.deviceType == getDataValue("deviceType")?.toInteger()))
						}
		return ( thisDevice ? true : false )				
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionReport cmd, ep = null ) 
{
	ConcurrentHashMap whatThisDeviceSent = supervisionSentCommands?.get(device.getDeviceNetworkId() as String)
	
	String whatWasSent = null

	switch (cmd.status as Integer)
	{
		case 0x00: // "No Support" 
			whatWasSent = whatThisDeviceSent?.remove(cmd.sessionID as Integer)
			if (ignoreSupervisionNoSupportCode()) {
				if (logEnable) log.warn "Received a 'No Support' supervision report ${cmd} for command ${whatWasSent}, but this device has known problems with its Supervision implementation so the 'No Support' code was ignored."
			} else 	{
				log.warn "Device ${device.displayName}: Z-Wave Command supervision reported as 'No Support' for command ${whatWasSent}. If you see this warning repeatedly, please report as an issue on https://github.com/jvmahon/HubitatCustom/issues. Please provide the manufacturer, deviceType, and deviceId code for your device as shown on the device's Hubitat device web page. Endpoint ${ep ?: 0}."
			}
			break
		case 0x01: // "working"
			whatWasSent = whatThisDeviceSent?.get(cmd.sessionID as Integer)
			if (txtEnable) log.info "Device ${device.displayName}: Still processing command: ${whatWasSent}. Endpoint ${ep ?: 0}."
			runIn(5, supervisionCheck)	
			break ;
		case 0x02: // "Fail"
			whatWasSent = whatThisDeviceSent?.remove(cmd.sessionID as Integer)
			log.warn "Device ${device.displayName}: Z-Wave supervised command reported failure. Failed command: ${whatWasSent}. Endpoint ${ep ?: 0}."
			sendUnsupervised(zwave.basicV1.basicGet(), ep)
			break
		case 0xFF: // "Success"
			whatWasSent = whatThisDeviceSent?.remove(cmd.sessionID as Integer)
			if (txtEnable || logEnable) log.info "Device ${device.displayName}: Device successfully processed supervised command ${whatWasSent}. Ep ${ep ?: 0}."
			break
	}
	if (whatThisDeviceSent?.size() < 1) unschedule(supervisionCheck)
}

void supervisionCheck() {
    // re-attempt once
	ConcurrentHashMap tryAgain = supervisionSentCommands?.get(device.getDeviceNetworkId() as String)
	tryAgain?.each{ sessionId, cmd ->
		log.warn "Device ${device.displayName}: Supervision Check is resending command: ${cmd} with sessionId: ${sessionId}"
		sendHubCommand(new hubitat.device.HubAction( cmd, hubitat.device.Protocol.ZWAVE)) 
		supervisionSentCommands?.get(device.getDeviceNetworkId() as String).remove(sessionId as Integer)
	}
}

//// Catch Event Not Otherwise Handled! /////

void zwaveEvent(hubitat.zwave.Command cmd, ep = null) {
    log.warn "Device ${device.displayName}: Received Z-Wave Message ${cmd} that is not handled by this driver. Endpoint: ${ep ?: 0}. Message class: ${cmd.class}."
}

///    Hail   ////
void zwaveEvent(hubitat.zwave.commands.hailv1.Hail cmd) {
	if (device.hasCapability("Refresh")) refresh()
}
