import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.util.concurrent.* 
import groovy.transform.Field

metadata {
    definition (name: "Generic Component Schlage BE469ZP Lock", namespace: "jvm", author: "jvm") {
		capability "Initialize"
        capability "Actuator"
        capability "Lock"
        capability "Lock Codes"
        capability "Refresh"
        capability "ContactSensor"

        attribute "lastCodeName", "STRING"
        
    }

    preferences{
        input name: "optEncrypt", type: "bool", title: "Enable lockCode encryption", defaultValue: false, description: ""
        //standard logging options for all drivers
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false, description: ""
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true, description: ""
    }
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}


void installed(){
    log.warn "installed..."
    sendEvent(name:"maxCodes",value:30)
    // sendEvent(name:"codeLength",value:4)
}
void initialize() {
    sendEvent(name:"maxCodes",value:30)

}

void updated() {
    log.info "updated..."

    //check crnt lockCodes for encryption status
    updateEncryption()
    //turn off debug logs after 30 minutes
    if (logEnable) runIn(1800,logsOff)
}

void parse(List description) {
    description.each {
        if (it.name == "parameterUpdate") {
            if (it.cmd?.parameterNumber == 16) {
                    Integer length = it.cmd.scaledConfigurationValue
            		sendEvent([name:"codeLength",value:length,descriptionText:"Device ${device.displayName}: codeLength set to ${length}"])
            } else if (it.cmd?.parameterNumber == 16) {
				log.debug "Set code length to ..."
			}
        } else if (device.hasAttribute (it.name)) {
            if (txtEnable) log.info it.descriptionText
            sendEvent(it)
        }
    }
}

//capability commands
void refresh() {
    sendEvent(name:"lock", value: device.currentValue("lock"))
}

void lock(){
	parent?.componentLock(this.device)
}

void unlock(){
	parent?.componentUnLock(this.device)
}

void setCodeLength(length){
	if ( (4..8).contains((int) length)) {
		parent?.setParameter(parameterNumber: 0x10, value:((int) length))
	} else {
        log.error "Device ${device.displayName}: Incorrect code length. Valid values are 4 to 8"
	}
}

//////////   Stuff above this line is working! /////////////


void setCode(codeNumber, code, name = null) {
    /*
	on sucess
		name		value								data												notes
		codeChanged	added | changed						[<codeNumber>":["code":"<pinCode>", "name":"<display name for code>"]]	default name to code #<codeNumber>
		lockCodes	JSON map of all lockCode
	*/
 	if (codeNumber == null || codeNumber == 0 || code == null) return

    if (logEnable) log.debug "setCode- ${codeNumber}"	
	
    if (!name) name = "code #${codeNumber}"

    Map lockCodes = getLockCodes()
    Map codeMap = getCodeMap(lockCodes,codeNumber)
    if (!changeIsValid(lockCodes,codeMap,codeNumber,code,name)) return
	
   	Map data = [:]
    String value
	
    if (logEnable) log.debug "setting code ${codeNumber} to ${code} for lock code name ${name}"

    if (codeMap) {
        if (codeMap.name != name || codeMap.code != code) {
            codeMap = ["name":"${name}", "code":"${code}"]
            lockCodes."${codeNumber}" = codeMap
            data = ["${codeNumber}":codeMap]
            if (optEncrypt) data = encrypt(JsonOutput.toJson(data))
            value = "changed"
        }
    } else {
        codeMap = ["name":"${name}", "code":"${code}"]
        data = ["${codeNumber}":codeMap]
        lockCodes << data
        if (optEncrypt) data = encrypt(JsonOutput.toJson(data))
        value = "added"
    }
    updateLockCodes(lockCodes)
    sendEvent(name:"codeChanged",value:value,data:data, isStateChange: true)
}

void deleteCode(codeNumber) {
parent?.componentDeleteCode(codeNumber)
}

//virtual test methods
void testSetMaxCodes(length){
    //on a real lock this event is generated from the response to a configuration report request
    sendEvent(name:"maxCodes",value:length)
}

void testUnlockWithCode(code = null){
    if (logEnable) log.debug "testUnlockWithCode: ${code}"
    /*
	lockCodes in this context calls the helper function getLockCodes()
	*/
    Object lockCode = lockCodes.find{ it.value.code == "${code}" }
    if (lockCode){
        Map data = ["${lockCode.key}":lockCode.value]
        String descriptionText = "${device.displayName} was unlocked by ${lockCode.value.name}"
        if (txtEnable) log.info "${descriptionText}"
        if (optEncrypt) data = encrypt(JsonOutput.toJson(data))
        sendEvent(name:"lock",value:"unlocked",descriptionText: descriptionText, type:"physical",data:data, isStateChange: true)
        sendEvent(name:"lastCodeName", value: lockCode.value.name, descriptionText: descriptionText, isStateChange: true)
    } else {
        if (txtEnable) log.debug "testUnlockWithCode failed with invalid code"
    }
}

//helpers
Boolean changeIsValid(lockCodes,codeMap,codeNumber,code,name){
    //validate proposed lockCode change
    Boolean result = true
    Integer maxCodeLength = device.currentValue("codeLength")?.toInteger() ?: 4
    Integer maxCodes = device.currentValue("maxCodes")?.toInteger() ?: 20
    Boolean isBadLength = code.size() > maxCodeLength
    Boolean isBadCodeNum = maxCodes < codeNumber
    if (lockCodes) {
        List nameSet = lockCodes.collect{ it.value.name }
        List codeSet = lockCodes.collect{ it.value.code }
        if (codeMap) {
            nameSet = nameSet.findAll{ it != codeMap.name }
            codeSet = codeSet.findAll{ it != codeMap.code }
        }
        Boolean nameInUse = name in nameSet
        Boolean codeInUse = code in codeSet
        if (nameInUse || codeInUse) {
            if (nameInUse) { log.warn "changeIsValid:false, name:${name} is in use:${ lockCodes.find{ it.value.name == "${name}" } }" }
            if (codeInUse) { log.warn "changeIsValid:false, code:${code} is in use:${ lockCodes.find{ it.value.code == "${code}" } }" }
            result = false
        }
    }
    if (isBadLength || isBadCodeNum) {
        if (isBadLength) { log.warn "changeIsValid:false, length of code ${code} does not match codeLength of ${maxCodeLength}" }
        if (isBadCodeNum) { log.warn "changeIsValid:false, codeNumber ${codeNumber} is larger than maxCodes of ${maxCodes}" }
        result = false
    }
    return result
}



void getCodes() {
	parent?.componentGetCodes(this.device, 5)
}

void mySettings() {
	parent?.testGetSettings(this)
}


void updateEncryption(){
    /*
	resend lockCodes map when the encryption option is changed
	*/
    String lockCodes = device.currentValue("lockCodes") //encrypted or decrypted
    if (lockCodes){
        if (optEncrypt && lockCodes[0] == "{") {	//resend encrypted
            sendEvent(name:"lockCodes",value: encrypt(lockCodes))
        } else if (!optEncrypt && lockCodes[0] != "{") {	//resend decrypted
            sendEvent(name:"lockCodes",value: decrypt(lockCodes))
        }
    }
}