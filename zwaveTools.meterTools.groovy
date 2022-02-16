library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Tools to Handle Zwave Meter Reports and Refreshes",
        name: "meterTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.hubTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/meterTools.groovy"
)
import groovy.lang.GString
import java.lang.String
//////////////////////////////////////////////////////////////////////
//////                         Handle  Meters                  ///////
//////////////////////////////////////////////////////////////////////

void	meterTools_refresh(Integer ep = null ) {
	// To make driver more generic, if meter type isn't known, then ask the device
	List<Integer> specifiedScales = getEndpointMetersSupported(ep)
	if (specifiedScales) { 
		meterTools_refreshScales(specifiedScales, ep)
	}	else  {
		advancedZwaveSend(zwave.meterV6.meterSupportedGet(), ep)
	}
}

void meterTools_refreshScales( List supportedScales, Integer ep = null ) 
{ // meterSupported is a List of supported scales - e.g., [2, 5, 6]]
	if (logEnable) log.debug "Device ${device.displayName}: Refreshing meter endpoint ${ep ?: 0} with scales ${supportedScales}"

	supportedScales.each{ scaleValue ->
		if ((scaleValue as Integer) <= 6) {
			advancedZwaveSend(zwave.meterV6.meterGet(scale: scaleValue), ep)
		} else {
			advancedZwaveSend(zwave.meterV6.meterGet(scale: 7, scale2: (scaleValue - 7) ), ep)
		}
	}
}

@groovy.transform.CompileStatic
List<Integer> getMeterScalesAsList(hubitat.zwave.commands.meterv6.MeterSupportedReport report){
	List<Integer> returnScales = []
	if ( report.scaleSupported & 0b00000001 ) { returnScales.add(0) } // kWh
	if ( report.scaleSupported & 0b00000010 ) { returnScales.add(1) } // kVAh
	if ( report.scaleSupported & 0b00000100 ) { returnScales.add(2) } // Watts
	if ( report.scaleSupported & 0b00001000 ) { returnScales.add(3) } // PulseCount
	if ( report.scaleSupported & 0b00010000 ) { returnScales.add(4) } // Volts
	if ( report.scaleSupported & 0b00100000 ) { returnScales.add(5) } // Amps
	if ( report.scaleSupported & 0b01000000 ) { returnScales.add(6) } // PowerFactor

	if ((( report.scaleSupported & 0b10000000 ) as Boolean ) && report.moreScaleTypes) {
		if ( report.scaleSupportedBytes[1] & 0b00000001 ) { returnScales.add(7)} // kVar
		if ( report.scaleSupportedBytes[1] & 0b00000010 ) { returnScales.add(8)} // kVarh
	}
	return returnScales
}

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterSupportedReport cmd, Integer ep = null ) { 

    if ((cmd.meterType as Integer) != 1 ){
		log.warn "Device ${device.displayName}: Received a meter support type of ${cmd.meterType} which is not processed by this code. Endpoint ${ep ?: 0}."
		return null
	}
	List<Integer> scaleList = getMeterScalesAsList(cmd)
	
	getThisEndpointData(ep ?: 0).put("metersSupported", scaleList) // Store in the device record so you don't have to ask device again!
	meterTools_refreshScales(scaleList, ep)
}

@groovy.transform.CompileStatic
Map getFormattedZWaveMeterReportEvent(hubitat.zwave.commands.meterv6.MeterReport cmd)
{
	BigDecimal meterValue = cmd.scaledMeterValue
	Integer deltaTime = cmd.deltaTime
	
	Map meterReport = [
		// Electric Meter
			1000:[name: "energy", 		value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Active Power Usage"],
			1100:[name: "energyConsumed", 	value: meterValue, deltaTime:deltaTime, unit: "kVAh", descriptionText:"Energy Consumed"], // custom attribute energyConsumed must be added to driver preferences
			1200:[name: "power", 		value: meterValue, deltaTime:deltaTime, unit: "W", descriptionText:"Watts"],
			1300:[name: "pulseCount", 	value: meterValue, deltaTime:deltaTime, unit: "Pulses", descriptionText:"Electric Meter - Pulse Count"], // custom attribute must be added to driver preferences
			1400:[name: "voltage", 		value: meterValue, deltaTime:deltaTime, unit: "V", descriptionText:"Current Voltage"],
			1500:[name: "amperage", 	value: meterValue, deltaTime:deltaTime, unit: "A", descriptionText:"Current Amperage"],
			1600:[name: "powerFactor", 	value: meterValue, deltaTime:deltaTime, unit: "Power Factor", descriptionText:"Power Factor"], // custom attribute must be added to driver preferences
			1700:[name: "reactiveCurrent", 	value: meterValue, deltaTime:deltaTime, unit: "KVar", descriptionText:"Reactive Current"], // custom attribute must be added to driver preferences
			1701:[name: "reactivePower", 	value: meterValue, deltaTime:deltaTime, unit: "KVarh", descriptionText:"Reactive Power"], // custom attribute must be added to driver preferences
		// Gas meter
			2000:[name: "gasFlow", 	value: meterValue, deltaTime:deltaTime, unit: "m3", descriptionText:"Gas volume."],
			2100:[name: "gasFlow", 	value: meterValue, deltaTime:deltaTime, unit: "ft3", descriptionText:"Gas volume"],
			2300:[name: "gasPulses", 	value: meterValue, deltaTime:deltaTime, unit: "pulses", descriptionText:"Gas MEter - Pulse Count"],
		// Water meter
			3000:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "m3", descriptionText:"Water Volume"],
			3100:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "ft3", descriptionText:"Water Volume"],
			3200:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "gpm", descriptionText:"Water flow US GPM"],			
			3300:[name: "rate", 		value: meterValue, deltaTime:deltaTime, unit: "pulses", descriptionText:"Water Meter - Pulse Count"],
		//Heating meter
			4000:[name: "heatingMeter", 	value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Heating"],
		// Cooling meter
			5000:[name: "coolingMeter", 	value: meterValue, deltaTime:deltaTime, unit: "kWh", descriptionText:"Cooling"],

	].get( ((int) cmd.meterType * 1000) + ((int) cmd.scale * 100) + ((int) (cmd.scale2 ?: 0)) )
	
	if (meterReport.is( null )) return null
	
	if (cmd.scaledPreviousMeterValue) 
		{
			String reportText = (String) meterReport.descriptionText + (String) " Prior value: ${cmd.scaledPreviousMeterValue}"
			meterReport += [previousValue:(cmd.scaledPreviousMeterValue), descriptionText:reportText]
		}
		
	return meterReport
}

void zwaveEvent(hubitat.zwave.commands.meterv6.MeterReport cmd, Integer ep = null )
{
	List<Map> events = []
	events << getFormattedZWaveMeterReportEvent(cmd)
	List<com.hubitat.app.DeviceWrapper> targetDevices = getChildDeviceListByEndpoint(ep)
	if ((ep ?: 0) == 0) { targetDevices += this }
	targetDevices.each{ it.parse( events ) }

}
