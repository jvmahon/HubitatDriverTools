metadata {
	definition (name: "Central Scene Test Driver",namespace: "jvm", author: "jvm", singleThreaded:false) {
		capability "Initialize"
		capability "Refresh"

		capability "PushableButton"
		capability "HoldableButton"
		capability "ReleasableButton"
		capability "DoubleTapableButton"	
    }
}

import java.util.concurrent.* 
import groovy.transform.Field



///////////////////////////////////////////////////////////////////////////////////////////////
///////////////                  Central Scene Processing          ////////////////////////////
///////////////////////////////////////////////////////////////////////////////////////////////

// Use a concurrentHashMap to hold the last reported state. This is used for "held" state checking
// In a "held" state, the device will send "held down refresh" messages at either 200 mSecond or 55 second intervals.
// Hubitat should not generated repreated "held" messages in response to a refresh, so inhibit those
// Since the concurrentHashMap is @Field static -- its data structure is common to all devices using this
// Driver, therefore you have to key it using the device.deviceNetworkId to get the value for a particuarl device.
@Field static  ConcurrentHashMap centralSceneButtonState = new ConcurrentHashMap<String, String>(64, 0.75, 1)

void centralSceneTools_initialize(){
	basicZwaveSend(zwave.centralSceneV3.centralSceneSupportedGet())
}

void initialize(){
	centralSceneTools_initialize()
}

String getCentralSceneButtonState(Integer button) { 
 	String key = "${device.deviceNetworkId}.Button.${button}"
	return centralSceneButtonState.get(key)
}

String setCentralSceneButtonState(Integer button, String state) {
 	String key = "${device.deviceNetworkId}.Button.${button}"
	centralSceneButtonState.put(key, state)
	return centralSceneButtonState.get(key)
}

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneSupportedReport  cmd) {
	log.debug "Device ${device.displayName}: received central scene supported Report ${cmd}"

	List<Map> events = []
	events << [name:"numberOfButtons", value: cmd.supportedScenes, descriptionText:"Reporting Number of Buttons:${cmd.supportedScenes}."]
	(childDevices + this).each{ it.parse(events) }
}

@groovy.transform.CompileStatic
void doubleTap(button) 	{ multiTap(button, 2)	}

@groovy.transform.CompileStatic
void push(button) 		{ multiTap(button, 1) }

void hold(button) 		{ 
		List<Map> events = []
		events <<	[name:"held", value:button, type:"digital", isStateChange: true , descriptionText:"Holding button ${button}." ]
		(childDevices + this).each{ it.parse(events) }
	}

void release(button) 	{ 
		List<Map> events  = []
		events << [name:"released", value:button, type:"digital", isStateChange: true , descriptionText:"Released button ${button}." ]
		(childDevices + this).each{ it.parse(events) }
	}

void multiTap(button, taps) {
	List<Map> events = []
	
	if (taps == 1) {
	    events << [name:"pushed", value:button, type:"digital", isStateChange: true , descriptionText:"Button ${button} pushed." ]
	} else if (taps == 2) {
		events << [name:"doubleTapped", value:button, type:"digital", isStateChange: true , descriptionText:"Button ${button} double-tapped."]
	}

    events << [[name:"multiTapButton", value:("${button}.${taps}" as Float), type:"physical", unit:"Button #.Tap Count", isStateChange: true , descriptionText:"Button ${button} tapped ${taps} times." ]]
	(childDevices + this).each{ it.parse(events) }
}

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd)
{
	log.debug "Device ${device.displayName}: received central scene notification ${cmd}"
	
	List<Map> events = []
	
	// Check if central scene is already in a held state, if so, and you get another held message, its a refresh, so don't send a sendEvent
	if ((getCentralSceneButtonState((Integer) cmd.sceneNumber) == "held") && (((Integer) cmd.keyAttributes) == 2)) return

	// Central scene events should be sent with isStateChange:true since it is valid to send two of the same events in a row (except held, whcih is handled in previous line)
    Map basicCCEvent = [value:cmd.sceneNumber, type:"physical", unit:"button#", isStateChange: true ]
	
	basicCCEvent.name = [	0:"pushed", 1:"released", 2:"held",  3:"doubleTapped", 
					4:"buttonTripleTapped", 5:"buttonFourTaps", 6:"buttonFiveTaps"].get((Integer)cmd.keyAttributes)
	
	String tapDescription = [	0:"Pushed", 1:"Released", 2:"Held",  3:"Double-Tapped", 
								4:"Three Taps", 5:"Four Taps", 6:"Five Taps"].get((Integer)cmd.keyAttributes)
    
	// Save the event name for event that is about to be sent using sendEvent. This is important for 'held' state refresh checking
	setCentralSceneButtonState((Integer) cmd.sceneNumber , basicCCEvent.name)	
	
	basicCCEvent.descriptionText="Button #${cmd.sceneNumber}: ${tapDescription}"

	events << basicCCEvent

	
	// Next code is for the custom attribute "multiTapButton".
	Integer taps = [0:1, 3:2, 4:3, 5:4, 6:5].get((Integer)cmd.keyAttributes)
	if ( taps) {
		events << [ name:"multiTapButton", value:("${cmd.sceneNumber}.${taps}" as Float), 
					unit:"Button.Taps", type:"physical", 
					descriptionText:"MultiTapButton ${cmd.sceneNumber}.${taps}", isStateChange: true  ]
	} 
	(childDevices + this).each{ it.parse(events) }
}


//////////////////////////////////////////////////////////////////////
//////        Helper Functions for Sending and Receiving           ///////
////////////////////////////////////////////////////////////////////// 


// create getUserParseMap() in driver to override.
@groovy.transform.CompileStatic
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
		0x72: 2, // Manufacturer Specific
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
@groovy.transform.CompileStatic
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