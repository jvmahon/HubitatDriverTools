library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Tools to Send and Receive from Z-Wave Devices",
        name: "sendReceiveTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "none",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/sendReceiveTools.groovy"
)

import java.util.concurrent.* 
import groovy.transform.Field

// create getUserParseMap() in driver to override.
Map getDefaultParseMap () {
	return [
		0x20:2, // Basic Set
		0x25:2, // Switch Binary
		0x26:4, // Switch MultiLevel 
		0x31:11, // Sensor MultiLevel
		0x32:6, // Meter
		0x5B:3,	// Central Scene
		0x60:4,	// MultiChannel
		0x62:1,	// Door Lock
		0x63:1,	// User Code
		0x6C:1,	// Supervision
		0x71:8, // Notification
		0x72: 1, // Manufacturer Specific
		0x80:3, // Battery
		0x86:3,	// Version
		0x98:1,	// Security
		0x9B:2,	// Configuration
		0x87:3  // Indicator
		]
}
		
////    Z-Wave Message Parsing   ////
// create userDefinedParseFilter to override
void parse(String description) {
		hubitat.zwave.Command cmd = zwave.parse(description, (userParseMap ?: defaultParseMap))
		if (userDefinedParseFilter) cmd = userDefinedParseFilter(cmd, description)
		if (cmd) { zwaveEvent(cmd) }
}



void parse(List<Map> listOfEvents) {
    listOfEvents.each {
        if (device.hasAttribute(it.name)) {
            if (txtEnable && it.descriptionText) log.info it.descriptionText
            sendEvent(it)
        }
    }
}

String secure(hubitat.zwave.Command cmd, Integer ep = null ){ 
	if (ep) {
		return zwaveSecureEncap(zwave.multiChannelV4.multiChannelCmdEncap(sourceEndPoint: 0, bitAddress: 0, res01:0, destinationEndPoint: ep).encapsulate(cmd))
	} else {
		return zwaveSecureEncap(cmd) 
	}
}

// a simple unsupervised send with endpoint support
void basicZwaveSend(hubitat.zwave.Command cmd, Integer ep = null ) { 
	sendHubCommand(new hubitat.device.HubAction( secure(cmd, ep), hubitat.device.Protocol.ZWAVE)) 
}

////    Security Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
	hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand( (userParseMap ?: defaultParseMap) )
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand) }
}

////    Multi-Channel Encapsulation   ////
void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
    hubitat.zwave.Command  encapsulatedCommand = cmd.encapsulatedCommand((userParseMap ?: defaultParseMap))
    if (encapsulatedCommand) { zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint) }
}


//// Catch Event Not Otherwise Handled! /////
void zwaveEvent(hubitat.zwave.Command cmd, Integer ep = null ) {
    log.warn "Device ${device.displayName}: Received Z-Wave Message ${cmd} that is not handled by this driver. Endpoint: ${ep ?: 0}. Message class: ${cmd.class}."
}

//////////////////////////////////////////////////////////////////////
//////        Handle Supervision request and reports           ///////
////////////////////////////////////////////////////////////////////// 

// @Field static results in variable being shared among all devices that use the same driver, so I use a concurrentHashMap keyed by a device's deviceNetworkId to get a unqiue value for a particular device
// supervisionSessionIDs stores the last used sessionID value (0..63) for a device. It must be incremented mod 64 on each send
// supervisionSentCommands stores the last command sent
// Each is initialized for 64 devices, but can automatically grow
@Field static ConcurrentHashMap<String, Integer> supervisionSessionIDs = new ConcurrentHashMap<String, Integer>(64, 0.75, 1)
@Field static ConcurrentHashMap<String, ConcurrentHashMap> supervisionSentCommands = new ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>>(64, 0.75, 1)

Integer getNewSessionId() {
		// Get the next session ID mod 64, but if there is no stored session ID, initialize it with a random value.
		Integer lastSessionID = supervisionSessionIDs.get(device.getDeviceNetworkId() ,((Integer)(Math.random() * 64) % 64)  )
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
	if (userSupervisionBrokenMap) {supervisionBroken += userSupervisionBrokenMap}
	
	Map thisDevice =	supervisionBroken.find{ ((it.manufacturer == mfr ) && (it.deviceId == deviceId ) && (it.deviceType == deviceType)) }
	
	if (thisDevice && logEnable) log.warn "Device ${device.displayName}: This device is on the Never Supervise list. Check manufacturer for a firmware update. Not supervising."
	return ( thisDevice ? true : false )
}

Boolean ignoreSupervisionNoSupportCode()
{
	// Some devices implement the Supervision command class incorrectly and return a "No Support" code even when they work.
	// This function is to ignore the No Support code from those devices.
	Integer mfr = 			getDataValue("manufacturer")?.toInteger()
	Integer deviceId = 		getDataValue("deviceId")?.toInteger()
	Integer deviceType =	getDataValue("deviceType")?.toInteger()
	List<Map> poorSupervisionSupport = [
			[	manufacturer:12,  	deviceType:17479,	deviceId: 12340  	], // HomeSeer WD100 S2 is buggy!
			[	manufacturer:12,  	deviceType:17479,	deviceId: 12342  	], // HomeSeer WD200 is buggy!
			]
			
	if (userPoorSupervisionSupportMap) {poorSupervisionSupport += userPoorSupervisionSupportMap}
	
    Map thisDevice =	poorSupervisionSupport.find{((it.manufacturer == mfr ) && (it.deviceId == deviceId ) && (it.deviceType == deviceType)) }
	if (thisDevice && logEnable) log.warn "Device ${device.displayName}: This device is on the Poor Supervise Support list. Check manufacturer for a firmware update. Ignoring 'No Support' return codes."

		return ( thisDevice ? true : false )				
}

Boolean getSuperviseThis() {
		if (neverSupervise) return false
		return (getDataValue("S2")?.toInteger() != null )
}

void advancedZwaveSend(Map inputs = [:]) { 
	Map params = [ cmd: null , onSuccess: null , onFailure: null , delay: null , ep: null ]
	params << inputs
	List<String> superviseThese = [ "2501", // Switch Binary
									"2601", "2604", "2605", //  Switch MultiLevel 
									"7601", // Lock V1
									"3305", // Switch Color Set									
									]
	if (userDefinedSupervisionList) superviseThese += userDefinedSupervisionList								
	if (superviseThese.contains(params.cmd.CMD)) {
		sendSupervised(params)
	} else {
		sendUnsupervised(params)
	}
}
void advancedZwaveSend(hubitat.zwave.Command cmd, Integer ep = null ) { 
	advancedZwaveSend(cmd:cmd, ep:ep)
}

void sendSupervised(hubitat.zwave.Command cmd, Integer ep = null ) { 
    sendSupervised(cmd:cmd, ep:ep)
}

void sendSupervised(Map inputs = [:]) { 
	Map params = [ cmd: null , onSuccess: null , onFailure: null , delay: null , ep: null ]
	params << inputs
	if (!(params.cmd instanceof hubitat.zwave.Command )) { 
		log.error "SendSupervised called with improper command type"
		return 
		}
	if (params.onSuccess && !(params.onSuccess instanceof hubitat.zwave.Command )) { 
		log.error "SendSupervised called with improper onSuccess command type"
		return 
		}
	if (params.onFailure && !(params.onFailure instanceof hubitat.zwave.Command )) { 
		log.error "SendSupervised called with improper onFailure command type"
		return 
		}
		
	if (superviseThis) {
		Integer thisSessionId = getNewSessionId()
		ConcurrentHashMap commandStorage = supervisionSentCommands.get(device.getDeviceNetworkId() , new ConcurrentHashMap<Integer, hubitat.zwave.Command>(64, 0.75, 1))
		
		hubitat.zwave.Command  supervisedCommand = zwave.supervisionV1.supervisionGet(sessionID: thisSessionId, statusUpdates: true ).encapsulate(params.cmd)
		
		commandStorage.put(thisSessionId, [cmd:(params.cmd), onSuccess: (params.onSuccess), onFailure:(params.onFailure), ep:(params.ep), attempt:1, sessionId:thisSessionId]) 
		
		basicZwaveSend(supervisedCommand, ep)	
		
		Integer retryTime	=  Math.min( Math.max( (s2RetryPeriod ?: 2000), 500), 5000)
		runInMillis( retryTime, supervisionCheck)	

	} else {
		basicZwaveSend(params.cmd, params.ep)
	}
}
void sendUnsupervised(hubitat.zwave.Command cmd, Integer ep = null ) { 
	basicZwaveSend(cmd, ep)
}
void sendUnsupervised(Map inputs = [:]) { 
	Map params = [ cmd: null , onSuccess: null , onFailure: null , delay: null , ep: null ]
	params << inputs
	basicZwaveSend(params.cmd, params.ep)
}

// This handles a supervised message (a "get") received from the Z-Wave device //
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, Integer ep = null ) {
    hubitat.zwave.Command encapsulatedCommand = cmd.encapsulatedCommand((userParseMap ?: defaultParseMap))
	
    if (encapsulatedCommand) {
		if ( ep ) {
			zwaveEvent(encapsulatedCommand, ep)
		} else {
			zwaveEvent(encapsulatedCommand)
		}
    }
	
	basicZwaveSend( new hubitat.zwave.commands.supervisionv1.SupervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0), ep)
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionReport cmd, Integer ep = null ) 
{
	ConcurrentHashMap whatThisDeviceSent = supervisionSentCommands?.get(device.getDeviceNetworkId() )
    
	Map whatWasSent = whatThisDeviceSent.get((Integer) cmd.sessionID)

    if (!whatWasSent) {
        log.warn "Device ${device.displayName}: Received SuperVision Report ${cmd} for endpoint ${ep},  but what was sent is null. May have been processed by a duplicate SuperVision Report. If this happens repeatedly, report issue at https://github.com/jvmahon/HubitatDriverTools/issues"
        return
    }
    
	switch ((Integer) cmd.status)
	{
		case 0x00: // "No Support" 
			whatWasSent = whatThisDeviceSent?.remove((Integer)cmd.sessionID)
			if (ignoreSupervisionNoSupportCode()) {
                if (logEnable) log.warn "Device ${device.displayName}: Received a 'No Support' supervision report ${cmd} for command ${whatWasSent}, but this device has known problems with its Supervision implementation so the 'No Support' code was ignored."
				if (whatWasSent.onSuccess) basicZwaveSend(whatWasSent.onSuccess, whatWasSent.ep) 
				
			} else 	{
				log.warn "Device ${device.displayName}: Z-Wave Command supervision reported as 'No Support' for command ${whatWasSent}. If you see this warning repeatedly, please report as an issue at https://github.com/jvmahon/HubitatDriverTools/issues. Please provide the manufacturer, deviceType, and deviceId code for your device as shown on the device's Hubitat device web page."
				if (whatWasSent.onFailure) basicZwaveSend(whatWasSent.onFailure, whatWasSent.ep) 
			}
			break
		case 0x01: // "working" - Remove check if you get back a "working" status since you know device is processing the command.
			whatWasSent = whatThisDeviceSent?.get((Integer)cmd.sessionID)
			if (txtEnable) log.info "Device ${device.displayName}: Still processing command: ${whatWasSent}."
			runInMillis(5000, supervisionCheck)	
			break ;
		case 0x02: // "Fail"
			whatWasSent = whatThisDeviceSent?.remove((Integer) cmd.sessionID)
			log.warn "Device ${device.displayName}: Z-Wave supervised command reported failure. Failed command: ${whatWasSent}."
			if (whatWasSent.onFailure) basicZwaveSend(whatWasSent.onFailure, whatWasSent.ep) 

			break
		case 0xFF: // "Success"
			whatWasSent = whatThisDeviceSent?.remove((Integer) cmd.sessionID)
			if (txtEnable || logEnable) log.info "Device ${device.displayName}: Device successfully processed supervised command ${whatWasSent}."
			if (whatWasSent.onSuccess) basicZwaveSend(whatWasSent.onSuccess, whatWasSent.ep) 
			break
	}
	if (whatThisDeviceSent?.size() < 1) unschedule(supervisionCheck)
}

void supervisionCheck() {
    // re-attempt supervison once, else send without supervision
	ConcurrentHashMap tryAgain = supervisionSentCommands?.get(device.getDeviceNetworkId())
	tryAgain?.each{ thisSessionId, whatWasSent ->
		
		Integer retries = Math.min( Math.max( (s2MaxRetries ?: 2), 1), 5)
		if (whatWasSent.attempt <  retries ) {
			whatWasSent.attempt += 1
			hubitat.zwave.Command  supervisedCommand = zwave.supervisionV1.supervisionGet(sessionID: thisSessionId, statusUpdates: true ).encapsulate(whatWasSent.cmd)
			if (logEnable) log.debug "Device ${device.displayName}: Reattempting command ${whatWasSent}."

			basicZwaveSend(supervisedCommand, whatWasSent.ep)	
		} else {
			if (logEnable) log.debug "Device ${device.displayName}: Supervision command retries exceeded. Resending command without supervision. Command ${whatWasSent}."
			basicZwaveSend(whatWasSent.cmd, whatWasSent.ep)
			supervisionSentCommands?.get(device.getDeviceNetworkId()).remove((Integer) thisSessionId)		
		}
	}
}



