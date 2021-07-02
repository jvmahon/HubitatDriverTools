library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handles Zwave Notifications",
        name: "meterTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/meterTools.groovy"
)
//////////////////////////////////////////////////////////////////////
//////                         Handle  Meters                  ///////
//////////////////////////////////////////////////////////////////////

void	meterTools_refresh(ep = null ) {
	// To make driver more generic, if meter type isn't known, then ask the device
	List specifiedScales = thisDeviceDataRecord?.endpoints.get((ep ?: 0) as Integer)?.metersSupported
	if (logEnable) log.debug "Refreshing a meter with scales ${specifiedScales}"
	if (specifiedScales)
	{ 
		meterRefresh(specifiedScales, ep)
	}	else  {
		sendUnsupervised(zwave.meterV6.meterSupportedGet(), ep)
	}
}

// Next function is not currently used! 
void meterTools_refreshScales( List supportedScales, ep = null ) 
{ // meterSupported is a List of supported scales - e.g., [2, 5, 6]]
	if (logEnable) log.debug "Refreshing a meter with scales ${supportedScales}"

	supportedScales.each{ scaleValue ->
		if ((scaleValue as Integer) <= 6) {
			sendUnsupervised(zwave.meterV6.meterGet(scale: scaleValue), ep)
		} else {
			sendUnsupervised(zwave.meterV6.meterGet(scale: 7, scale2: (scaleValue - 7) ), ep)
		}
	}
}

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterSupportedReport report, ep = null )
{ 
    if ((report.meterType as Integer) != 1 ){
		log.warn "Device ${device.displayName}: Received a meter support type of ${report.meterType} which is not processed by this code. Endpoint ${ep ?: 0}"
		return null
	}
	
	List<Integer> scaleList = []

	if (( report.scaleSupported & 0b00000001 ) as Boolean ) { scaleList.add(0) } // kWh
	if (( report.scaleSupported & 0b00000010 ) as Boolean ) { scaleList.add(1) } // kVAh
	if (( report.scaleSupported & 0b00000100 ) as Boolean ) { scaleList.add(2) } // Watts
	if (( report.scaleSupported & 0b00001000 ) as Boolean ) { scaleList.add(3) } // PulseCount
	if (( report.scaleSupported & 0b00010000 ) as Boolean ) { scaleList.add(4) } // Volts
	if (( report.scaleSupported & 0b00100000 ) as Boolean ) { scaleList.add(5) } // Amps
	if (( report.scaleSupported & 0b01000000 ) as Boolean ) { scaleList.add(6) } // PowerFactor

	if ((( report.scaleSupported & 0b10000000 ) as Boolean ) && report.moreScaleTypes) {
		if (( report.scaleSupportedBytes[1] & 0b00000001 ) as Boolean) { scaleList.add(7)} // kVar
		if (( report.scaleSupportedBytes[1] & 0b00000010 ) as Boolean) { scaleList.add(8)} // kVarh
	}
	thisDeviceDataRecord.endpoints.get((ep ?: 0) as Integer).put("metersSupported", scaleList) // Store in the device record so you don't have to ask device again!
	meterRefresh(scaleList, ep)
}

Map getFormattedZWaveMeterReportEvent(def cmd)
{
	BigDecimal meterValue = cmd.scaledMeterValue
	Integer deltaTime = cmd.deltaTime
	
	Map meterReport = [
		1:[
			0000:[name: "energy", 		value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Active Power Usage"],
			1000:[name: "energyConsumed", 	value: meterValue, deltaTime:deltaTime, unit: "kVAh", descriptionText:"Energy Consumed"], // custom attribute energyConsumed must be added to driver preferences
			2000:[name: "power", 		value: meterValue, deltaTime:deltaTime, unit: "W", descriptionText:"Watts"],
			3000:[name: "pulseCount", 	value: meterValue, deltaTime:deltaTime, unit: "Pulses", descriptionText:"Electric Meter - Pulse Count"], // custom attribute must be added to driver preferences
			4000:[name: "voltage", 		value: meterValue, deltaTime:deltaTime, unit: "V", descriptionText:"Current Voltage"],
			5000:[name: "amperage", 	value: meterValue, deltaTime:deltaTime, unit: "A", descriptionText:"Current Amperage"],
			6000:[name: "powerFactor", 	value: meterValue, deltaTime:deltaTime, unit: "Power Factor", descriptionText:"Power Factor"], // custom attribute must be added to driver preferences
			7000:[name: "reactiveCurrent", 	value: meterValue, deltaTime:deltaTime, unit: "KVar", descriptionText:"Reactive Current"], // custom attribute must be added to driver preferences
			7001:[name: "reactivePower", 	value: meterValue, deltaTime:deltaTime, unit: "KVarh", descriptionText:"Reactive Power"], // custom attribute must be added to driver preferences
		], 
		2:[ // Gas meter
			0000:[name: "gasFlow", 	value: meterValue, deltaTime:deltaTime, unit: "m3", descriptionText:"Gas volume."],
			1000:[name: "gasFlow", 	value: meterValue, deltaTime:deltaTime, unit: "ft3", descriptionText:"Gas volume"],
			3000:[name: "gasPulses", 	value: meterValue, deltaTime:deltaTime, unit: "pulses", descriptionText:"Gas MEter - Pulse Count"],
		],
		3:[ // Water meter
			0000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "m3", descriptionText:"Water Volume"],
			1000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "ft3", descriptionText:"Water Volume"],
			2000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "gpm", descriptionText:"Water flow"],			
			3000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "pulses", descriptionText:"Water Meter - Pulse Count"],
		],
		4:[ //Heating meter
			0000:[name: "heatingMeter", 	value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Heating"],
		],
		5:[ // Cooling meter
			0000:[name: "coolingMeter", 	value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Cooling"],
		]
	].get(cmd.meterType as Integer)?.get( ( (cmd.scale as Integer) * 1000 + ((cmd.scale2 ?: 0) as Integer)))
	
	if (meterReport.is( null )) return null
	
	if (cmd.scaledPreviousMeterValue) 
		{
			String reportText = meterReport.descriptionText + " Prior value: ${cmd.scaledPreviousMeterValue}"
			meterReport += [previousValue:(cmd.scaledPreviousMeterValue), descriptionText:reportText]
		}
		
	meterReport += [deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]

	return meterReport
}

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterReport cmd, ep = null )
{
	List<com.hubitat.app.DeviceWrapper> targetDevices = getTargetDeviceListByEndPoint(ep)

	Map thisEvent = getFormattedZWaveMeterReportEvent(cmd)
	if (logEnable) log.debug "Responding to a meter report ${cmd} with event ${thisEvent}"
	if ( ! thisEvent ) { 
		if ( logEnable ) log.debug "Device ${device.displayName}: Received an unhandled report ${cmd} for its endpoint ${ep}." 
	} else { 
		Boolean targetNotified = false
		targetDevices.each {
			if (it.hasAttribute(thisEvent.name)) { 
				it.sendEvent(thisEvent) 
				targetNotified = true
			}
		}
		if (! targetNotified) {
				log.warn "Device ${device.displayName}: Device does not support attribute ${thisEvent.name}, endpoint ${ep?:0}, Zwave report: ${cmd}."
			}
	}	
}
