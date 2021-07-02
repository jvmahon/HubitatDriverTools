library (
        base: "driver",
        author: "jvm33",
        category: "zwave",
        description: "Handle Binary and MultiLevel Sensors",
        name: "sensorTools",
        namespace: "zwaveTools",
        documentationLink: "https://github.com/jvmahon/HubitatDriverTools",
		version: "0.0.1",
		dependencies: "zwaveTools.endpointTools",
		librarySource:"https://raw.githubusercontent.com/jvmahon/HubitatDriverTools/main/sensorTools.groovy"
)

//////////////////////////////////////////////////////////////////////
//////        Handle   Binary Sensor     ///////
//////////////////////////////////////////////////////////////////////
void sensorTools_refresh(ep = null ) {
	binarySensorRefresh(ep)
	multilevelSensorRefresh(ep)

}
void	binarySensorRefresh(ep = null ) {
	log.debug "Device ${device}: binarySensorRefresh function is not implemented."
}

void multilevelSensorRefresh(ep) {
	log.debug "Device ${device}: multilevelSensorRefresh function is not implemented."
}

Map getFormattedZWaveSensorBinaryEvent(def cmd)
{
	Map returnEvent = [ 	
			2:[ // Smoke
				0:[name:"smoke" , value:"clear", descriptionText:"Smoke detector status Idle."],
				255:[name:"smoke" , value:"detected", descriptionText:"Smoke detected."], 
				],
			3:[ // CO
				0:[name:"carbonMonoxide" , value:"clear", descriptionText:"Carbon Monoxide status."],
				255:[name:"carbonMonoxide" , value:"detected", descriptionText:"Carbon Monoxide detected."],
				],
			4:[ // CO2
				0:[name:"carbonDioxideDetected" , value:"clear", descriptionText:"Carbon Dioxide status."],	
				255:[name:"carbonDioxideDetected" , value:"detected", descriptionText:"Carbon Dioxide detected."],
				],					
			6:[ // Water
				0:[name:"water" , value:"dry", descriptionText:"Water Alarm Notification, Status Dry."],
				255:[name:"water" , value:"wet", descriptionText:"Water leak detected."],
				],
			8:[ // Tamper
				0:[name:"tamper" , value:"clear", descriptionText:"Tamper state cleared."],
				255:[name:"tamper" , value:"detected", descriptionText:"Tampering, device cover removed"], 
				],
			10:[ // Door/Window
				0:[name:"contact" , value:"closed", descriptionText:"Contact sensor, closed"], 					
				255:[name:"contact" , value:"open", descriptionText:"Contact sensor, open"], 					
				],
			12:[ // Motion
				0:[name:"motion" , value:"inactive", descriptionText:"Motion Inactive."],
				255:[name:"motion" , value:"active", descriptionText:"Motion detected."],
				]
				
		].get(cmd.sensorType as Integer)?.get(cmd.sensorValue as Integer)
		
		if (returnEvent.is( null ) ) return null
		return returnEvent + [deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
}

void zwaveEvent(hubitat.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd, ep = null )
{
	List<com.hubitat.app.DeviceWrapper> targetDevices = getTargetDeviceListByEndPoint(ep)

	if (logEnable) log.debug "Device ${device.displayName}: Received SensorBinaryReport: ${cmd} for endpoint ${ep ?: 0}."
	
	Map thisEvent = getFormattedZWaveSensorBinaryEvent(cmd)
	
	if ( ! thisEvent ) { 
		if ( logEnable ) log.debug "Device ${targetDevice.displayName}: Received an unhandled report ${cmd} for endpoint ${ep}." 
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
//////////////////////////////////////////////////////////////////////
//////        Handle  Multilevel Sensor       ///////
//////////////////////////////////////////////////////////////////////
Map getFormattedZWaveSensorMultilevelReportEvent(def cmd)
{
	Map tempReport = [
		1: [name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Air temperature"], 
		23:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Water temperature"], 
		24:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Soil temperature"], 
		34:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Target temperature"], 
		62:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Boiler Water temperature"],
		63:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Domestic Hot Water temperature"], 
		64:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Outside temperature"], 
		65:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Exhaust temperature"],
		72:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Return Air temperature"],		
		73:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Supply Air temperature"],		
		74:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Condenser Coil temperature"],		
		75:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Evaporator Coil temperature"],		
		76:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Liquid Line temperature"],
		77:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Discharge Line temperature"],
		80:[name: "temperature", value: null , unit: "°${temperatureScale}", descriptionText:"Defrost temperature"],	
	].get(cmd.sensorType as Integer)

	if (tempReport) {
		tempReport.value = convertTemperatureIfNeeded(cmd.scaledMeterValue, (((cmd.scale as Integer) == 0) ? "C" : "F"), 2)
		return tempReport + [deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
	}
	
	Map otherSensorReport = [
		3000:[name: "illuminance", value: cmd.scaledMeterValue, unit: "%"], // Illuminance
		3001:[name: "illuminance", value: cmd.scaledMeterValue, unit: "lx"],
		4000:[name: "power", value: cmd.scaledMeterValue, unit: "W"],
		4001:[name: "power", value: cmd.scaledMeterValue, unit: "BTU/h"],
		5000:[name: "humidity", value: cmd.scaledMeterValue, unit: "%"],
		5001:[name: "humidity", value: cmd.scaledMeterValue, unit: "g/m3"],
		8000:[name: "pressure", value: (cmd.scaledMeterValue * ((cmd.scale == 0) ? 1000 : 3386.38867)), unit:"Pa", descriptionText:"Atmospheric Pressure"],
		9000:[name: "pressure", value: (cmd.scaledMeterValue * ((cmd.scale == 0) ? 1000 : 3386.38867)), unit:"Pa", descriptionText:"Barometric Pressure"],
		15000:[name: "voltage", value: cmd.scaledMeterValue, unit: "V"],
		15001:[name: "voltage", value: cmd.scaledMeterValue, unit: "mV"],
		16000:[name: "amperage", value: cmd.scaledMeterValue, unit: "A"],
		16001:[name: "amperage", value: cmd.scaledMeterValue, unit: "mA"],
		17000:[name: "carbonDioxide ", value: cmd.scaledMeterValue, unit: "ppm"],
		27000:[name: "ultravioletIndex", value: cmd.scaledMeterValue, unit: "UV Index"],
		40000:[name: "carbonMonoxide ", value: cmd.scaledMeterValue, unit: "ppm"],
		56000:[name: "rate", value: cmd.scaledMeterValue, unit: "LPH"], // Water flow	
		58000:[name: "rssi", value: cmd.scaledMeterValue, unit: "%"],
		58001:[name: "rssi", value: cmd.scaledMeterValue, unit: "dBm"],
		67000:[name: "pH", value: cmd.scaledMeterValue, unit: "pH"],
	].get((cmd.sensorType * 1000 + cmd.scale) as Integer)	
	
	if (otherSensorReport) { 
		return otherSensorReport + [deviceType:"ZWV", zwaveOriginalMessage:cmd.format()]
	}
	
	return null
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv11.SensorMultilevelReport cmd, ep = null )
{
	List<com.hubitat.app.DeviceWrapper> targetDevices = getTargetDeviceListByEndPoint(ep)
	Map thisEvent = getFormattedZWaveSensorMultilevelReportEvent(cmd)
		
	if ( ! thisEvent ) { 
		if ( logEnable ) log.debug "Device ${device.displayName}: Received an unhandled report ${cmd} for endpoint ${ep}." 
	} else { 
		Boolean AnyTargetNotified = false
		targetDevices.each {
			if (it.hasAttribute(thisEvent.name)) { 
				it.sendEvent(thisEvent) 
				AnyTargetNotified = true
			}
		}
		if (! AnyTargetNotified) {
				log.warn "Device ${device.displayName}: Device does not support attribute ${thisEvent.name}, endpoint ${ep?:0}, Zwave report: ${cmd}."
			}
	}
}
