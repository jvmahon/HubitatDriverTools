library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Zwave Central Scene reports",
        name: "centralSceneTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.sendReceiveTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/centralSceneTools.groovy"
)
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
	advancedZwaveSend(zwave.centralSceneV3.centralSceneSupportedGet())
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
	sendEventToAll(event:[name:"numberOfButtons", value: cmd.supportedScenes])
}



void doubleTap(button) 	{ multiTap(button, 2)	}
void push(button) 		{ multiTap(button, 1) }
void hold(button) 		{ sendEventToAll([name:"held", value:button, type:"digital", isStateChange: true ]      )}
void release(button) 	{ sendEventToAll([name:"released", value:button, type:"digital", isStateChange: true ] )}

void multiTap(button, taps) {
	if (taps == 1) {
	    sendEventToAll([name:"pushed", value:button, type:"digital", isStateChange: true ])	
	} else if (taps == 2) {
		sendEventToAll([name:"doubleTapped", value:button, type:"digital", isStateChange: true ])	
	}

    sendEventToAll(event:[name:"multiTapButton", value:("${button}.${taps}" as Float), type:"physical", unit:"Button #.Tap Count", isStateChange: true ])		
}

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd)
{

	// Check if central scene is already in a held state, if so, and you get another held message, its a refresh, so don't send a sendEvent
	if ((getCentralSceneButtonState(cmd.sceneNumber as Integer) == "held") && (cmd.keyAttributes == 2)) return

	// Central scene events should be sent with isStateChange:true since it is valid to send two of the same events in a row (except held, whcih is handled in previous line)
    Map event = [value:cmd.sceneNumber, type:"physical", unit:"button#", isStateChange:true]
	
	event.name = [	0:"pushed", 1:"released", 2:"held",  3:"doubleTapped", 
					4:"buttonTripleTapped", 5:"buttonFourTaps", 6:"buttonFiveTaps"].get(cmd.keyAttributes as Integer)
	
	String tapDescription = [	0:"Pushed", 1:"Released", 2:"Held",  3:"Double-Tapped", 
								4:"Three Taps", 5:"Four Taps", 6:"Five Taps"].get(cmd.keyAttributes as Integer)
    
	// Save the event name for event that is about to be sent using sendEvent. This is important for 'held' state refresh checking
	setCentralSceneButtonState(cmd.sceneNumber, event.name)	
	
	event.descriptionText="${device.displayName}: Button #${cmd.sceneNumber}: ${tapDescription}"

	sendEventToAll(event:event)
	
	// Next code is for the custom attribute "multiTapButton".
	Integer taps = [0:1, 3:2, 4:3, 5:4, 6:5].get(cmd.keyAttributes as Integer)
	if ( taps)
	{
		event.name = "multiTapButton"
		event.unit = "Button #.Tap Count"
		event.value = ("${cmd.sceneNumber}.${taps}" as Float)
		sendEventToAll(event:event)
	} 
}
