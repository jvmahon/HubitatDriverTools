library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Basic Lock Processing ",
        name: "lockTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.sendReceiveTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/lockTools.groovy"
)
////    Send Simple Z-Wave Commands to Device  ////	
import groovy.json.JsonSlurper
import groovy.json.JsonOutput


//////////////////////////////////////////////////////////////////////
//////        Locks        ///////
//////////////////////////////////////////////////////////////////////
// import hubitat.zwave.commands.doorlockv1.*

void lockInitialize() {
	advancedZwaveSend( zwave.userCodeV1.usersNumberGet() )
}

void zwaveEvent(hubitat.zwave.commands.usercodev1.UsersNumberReport cmd) { 
	List events = [] << [name:"maxCodes", value: cmd.supportedUsers]	
	(childDevices + this).each{ it.parse(events) }
}


void locktools_refresh( ep = null ) {
	advancedZwaveSend(zwave.doorLockV1.doorLockOperationGet())
}

void componentLock(com.hubitat.app.DeviceWrapper cd) {lock(cd:cd)}
void lock(Map inputs = [:]) {
	Map params = [cd: null , duration: null ]	<< inputs
	Integer ep = getChildEndpointNumber(params.cd)

    advancedZwaveSend(cmd: zwave.doorLockV1.doorLockOperationSet(doorLockMode: 0xFF), onSuccess: zwave.doorLockV1.doorLockOperationGet(), onFailure: zwave.doorLockV1.doorLockOperationGet(), supervisionCheckDelay: 8, ep:ep)
}

void componentUnlock(com.hubitat.app.DeviceWrapper cd) {unlock(cd:cd)}
void unlock(Map inputs = [:]) {
	Map params = [cd: null , duration: null ]	<< inputs
	Integer ep = getChildEndpointNumber(params.cd)
    
	advancedZwaveSend(cmd: zwave.doorLockV1.doorLockOperationSet(doorLockMode: 0x00), onSuccess: zwave.doorLockV1.doorLockOperationGet(), onFailure: zwave.doorLockV1.doorLockOperationGet(), supervisionCheckDelay: 8, ep:ep )
}

void componentDeleteCode(cd, codeposition) {
log.debug "componenetDeleteCode was called."
}
void deleteCode(codeposition) {
    if (logEnable) log.debug "Device ${device.displayName}:  deleting code at position ${codeNumber}."
	// userIDStatus of 0 corresponds to Z-Wave  "Available (not set)" status.
	advancedZwaveSend(cmd: wave.userCodeV1.userCodeSet(userIdentifier:codeposition, userIdStatus:0), onSuccess: zwave.userCodeV1.userCodeGet(userIdentifier:codeposition), onFailure:zwave.userCodeV1.userCodeGet(userIdentifier:codeposition)  )
}

void componentGetCodes(cd, numberOfCodes) {
	log.debug "Getting codes for child device ${cd.displayName}"
	
	for( Integer thisCode = 1; thisCode <= (int) numberOfCodes; thisCode++) {
		log.debug "Getting code ${thisCode}"
		advancedZwaveSend(zwave.userCodeV1.userCodeGet(userIdentifier: thisCode))
	}
}

void componentSetCode(cd,codeposition, pincode, name) {
log.debug "componentSetCode was called."
}
void setCode(codeposition, pincode, name) {
	if (txtEnable) log.info "Device ${device.displayName}: setting code at position ${codeposition} to ${pincode}."

	advancedZwaveSend(zwave.userCodeV1.userCodeSet(userIdentifier:codeNumber, userIdStatus:0) )
	advancedZwaveSend(zwave.userCodeV1.userCodeSet(userIdentifier:codeposition, userIdStatus:1, userCode:(pincode as String) ))
	advancedZwaveSend(zwave.userCodeV1.userCodeGet(userIdentifier:codeposition))
}


void componentSetCodeLength(cd, pincodelength) {
log.debug "componentSetCode was called."
setCodeLength(pincodelength)
}
void setCodeLength(pincodelength) {
	log.warn "Device ${device.displayName}: Code Length command not supported. If your device supports code length settings, you may be able to set the code length using Z-Wave Parameter Settings controls from the component device."
}

// This is another form of door lock reporting. I believe its obsolete, but I've included it just in case some lock requires it.  
void zwaveEvent(hubitat.zwave.commands.doorlockv1.DoorLockOperationReport cmd) {
	List lockEvents = [] << [
		0x00: [name: "lock", value:"unlocked", descriptionText:"Door Unsecured."],
		0x01: [name: "lock", value:"unlocked with timeout", descriptionText:"Door Unsecured with timeout."],
		0x10: [name: "lock", value:"unlocked", descriptionText:"Door Unsecured for inside Door Handles."],
		0x11: [name: "lock", value:"unlocked with timeout"	, descriptionText:"Door Unsecured for inside Door Handles with timeout."],
		0x20: [name: "lock", value:"unlocked"	, descriptionText:"Door Unsecured for outside Door Handles."],
		0x21: [name: "lock", value:"unlocked with timeout", descriptionText:"Door Unsecured for outside Door Handles with timeout."],
		0xFF: [name: "lock", value:"locked", descriptionText:"Door Secured."],
		0xFE: [name: "lock", value:"unknown", descriptionText:"Unknown event."],
	].get(cmd.doorLockMode as Integer)
	
	(childDevices + this).each{ it.parse(lockEvents) }
}

// ===============
void componentDeleteCode(com.hubitat.app.DeviceWrapper cd, codeNumber) {
	Integer ep = getChildEndpointNumber(cd)
	
	advancedZwaveSend(zwave.userCodeV1.userCodeSet(userIdentifier:codeNumber, userIdStatus:0), ep )
	advancedZwaveSend(zwave.userCodeV1.userCodeGet(userIdentifier:codeposition), ep)
}


void zwaveEvent(hubitat.zwave.commands.usercodev1.UserCodeReport cmd, ep = null ) { 
	log.warn "Lock code is still under development. Functions may not be fully implemented."
	log.debug "For device ${device.displayName}, received User Code Report: " + cmd
	if ((int) cmd.userIdStatus == 0) {
		processDeleteCode((int) cmd.userIdentifier, ep)
	} else if ((int) cmd.userIdStatus == 1) {
		log.debug "UserCodeReport for user ID: ${cmd.userIdentifier} received code: ${cmd.userCode}."
	}
}

void testGetSettings(cd){
log.debug "Child device ${device.displayName}: settings are: " + cd.settings
}	
	
void processDeleteCode(codeNumber, ep) {
    /*
	on success
		name		value								data
		codeChanged	deleted								[<codeNumber>":["code":"<pinCode>", "name":"<display name for code>"]]
		lockCodes	[<codeNumber>":["code":"<pinCode>", "name":"<display name for code>"],<codeNumber>":["code":"<pinCode>", "name":"<display name for code>"]]
	*/
	List<com.hubitat.app.DeviceWrapper> allLocks = getChildDeviceListByEndpoint(ep).findAll{it.hasAttribute("lockCodes")}
	if (! allLocks) return
	
	com.hubitat.app.DeviceWrapper firstLock =	allLocks[0] // pick the first device to use! This is to ensure all devices stay in sync.
	Map allLockCodes = getLockCodes(firstLock)
	allLockCodes.remove("${codeNumber}")
	
    if (logEnable) log.debug "updateLockCodes: ${allLockCodes}"

	allLocks.each{
			String strCodes = JsonOutput.toJson(allLockCodes)
			if (it.settings.get("optEncrypt") ) { strCodes = encrypt(strCodes) }
			$ it.parse([[name:"lockCodes", value:strCodes, isStateChange:true]])
		}
}

Map getLockCodes(cd) {
    /*
	on a real lock we would fetch these from the response to a userCode report request
	*/
	if (!cd) return null
    String lockCodes = cd.currentValue("lockCodes")
    Map result = [:]
    if (lockCodes) {
        //decrypt codes if they're encrypted
        if (lockCodes[0] == "{") result = new JsonSlurper().parseText(lockCodes)
        else result = new JsonSlurper().parseText(decrypt(lockCodes))
    }
	if (logEnable) log.debug "Map of all lock codes in getLockCodes(cd) function is: ${result}"
    return result
}


Map getCodeMap(lockCodes,codeNumber){
    Map codeMap = [:]
    Map lockCode = lockCodes?."${codeNumber}"
    if (lockCode) {
        codeMap = ["name":"${lockCode.name}", "code":"${lockCode.code}"]
    }
    return codeMap
}

