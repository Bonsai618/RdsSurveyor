/*
 RDS Surveyor -- RDS decoder, analyzer and monitor tool and library.
 For more information see
   http://www.jacquet80.eu/
   http://rds-surveyor.sourceforge.net/

 Copyright (c) 2009, 2010 Christophe Jacquet

 This file is part of RDS Surveyor.

 RDS Surveyor is free software: you can redistribute it and/or modify
 it under the terms of the GNU Lesser Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 RDS Surveyor is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Lesser Public License for more details.

 You should have received a copy of the GNU Lesser Public License
 along with RDS Surveyor.  If not, see <http://www.gnu.org/licenses/>.

 */

package eu.jacquet80.rds.core;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.SimpleTimeZone;

import eu.jacquet80.rds.app.Application;
import eu.jacquet80.rds.app.InHouse;
import eu.jacquet80.rds.app.Paging;
import eu.jacquet80.rds.app.oda.AlertC;
import eu.jacquet80.rds.app.oda.ODA;
import eu.jacquet80.rds.app.oda.TDC;
import eu.jacquet80.rds.input.group.FrequencyChangeEvent;
import eu.jacquet80.rds.input.group.GroupEvent;
import eu.jacquet80.rds.input.group.GroupReaderEvent;
import eu.jacquet80.rds.input.group.GroupReaderEventVisitor;
import eu.jacquet80.rds.input.group.StationChangeEvent;
import eu.jacquet80.rds.log.ApplicationChanged;
import eu.jacquet80.rds.log.ClockTime;
import eu.jacquet80.rds.log.EONReturn;
import eu.jacquet80.rds.log.EONSwitch;
import eu.jacquet80.rds.log.GroupReceived;
import eu.jacquet80.rds.log.Log;
import eu.jacquet80.rds.log.RDSTime;
import eu.jacquet80.rds.log.StationLost;
import eu.jacquet80.rds.log.StationTuned;

public class GroupLevelDecoder {
	private int[] qualityHistory = new int[40];
	private int historyPtr = 0;
	private TunedStation station = null;  // realStation is used in case station is a dummy one
	private boolean synced = true;
	private Log log;

	// this allows to merge a bunch of "EON switch" messages together
	private int groupCountSinceEonSwitch = 0;

	private final String[] RP_TNGD_VALUES = {
			"No RP",
			"RP groups 00-99",
			"RP groups 00-39",
			"RP groups 40-99",
			"RP groups 40-69",
			"RP groups 70-99",
			"RP groups 00-19",
			"RP groups 20-39",
	};

	public GroupLevelDecoder(Log log) {
		this.log = log;
	}

	public void loseSync() {
		synced = false;
	}

	private int processBasicTuningBits(PrintWriter console, int block1, RDSTime time) {
		// Groups 0A, 0B, 15B : for TA, M/S and DI we need only block 1 (or block 3 for 15B)
		int ta = (block1>>4) & 1;
		int ms = (block1>>3) & 1;
		int addr = block1 & 3;

		station.setMusic(ms == 1);
		console.print("TA=" + ta + ", " + (ms==1 ? "M/s" : "m/S") + ", ");
		
		boolean diInfo = ((block1>>2) & 1) == 1;		
		station.setDIbit(addr, diInfo, console);

		boolean newTa = (ta == 1);

		if(station.getTP() && (station.getTA() != newTa)) {
			station.addTrafficEvent(time, (newTa ? "start" : "end") + " of traffic announcement");
		}

		station.setTA(ta == 1);

		// we return addr because it is also used to address PS segment in 0A/0B
		return addr;
	}

	private void processGroup(int nbOk, boolean[] blocksOk, int[] blocks, RDSTime time) {
		StringWriter consoleWriter = new StringWriter();
		PrintWriter console = new PrintWriter(consoleWriter);

		//console.print(" (" + (station == null ? null : station.getStationName() ) + ") ");
		Application newApp = null;

		TunedStation workingStation = station;

		qualityHistory[historyPtr] = nbOk;
		historyPtr = (historyPtr + 1) % qualityHistory.length;

		// First identify type and version of the group, if possible.
		// We do this now in order to be able to extract the PI from block 2
		// if version is B.
		int type = -1, version = -1;
		if(blocksOk[1]) {
			type = ((blocks[1]>>12) & 0xF);
			version = ((blocks[1]>>11) & 1);
		}


		int pi = -1;

		if(blocksOk[0]) {
			pi = blocks[0];
		} else if(version == 1 && blocksOk[2]) {
			pi = blocks[2];
		}

		if(pi != -1) {
			console.printf("PI=%04X", pi);

			String callsign = station.getCallsign();
			if(callsign != null) {
				console.printf(" [" + callsign + "]");
			}

			console.print(", ");

			if(station.getPI() == 0) {
				// new station
				station.setPI(pi);
			}
		} else console.print("         ");

		if(!synced) return;   // after a sync loss, we wait for a PI before processing further data

		if(blocksOk[1]) {
			workingStation.addGroupToStats(type, version, nbOk);

			int tp = (blocks[1]>>10) & 1;
			workingStation.setTP(tp == 1);

			int pty = (blocks[1]>>5) & 0x1F;
			workingStation.setPTY(pty);

			console.print("Type " + type + (char)('A' + version) + ", TP=" + tp + ", PTY=" + pty + ", ");
			//console.print("Group (" + (nbOk == 4 ? "full" : "part") + ") type " + type + (char)('A' + version) + ", TP=" + tp + ", PTY=" + pty + ", ");
		} else workingStation.addUnknownGroupToStats(nbOk);

		// initialize service stats
		ServiceStat serviceStat = new ServiceStat();
		serviceStat.add(ServiceStat.PI, 16);  // always a PI code on 16 bits
		serviceStat.add(ServiceStat.OVERHEAD, 5);	// group type: protocol overhead
		serviceStat.add(ServiceStat.PROG_TYPE, 5+1);	// PTY+TP: program type
		if(version == 1) serviceStat.add(ServiceStat.PI, 16);	// second PI code on B-type groups

		// Groups 0A & 0B
		if(type == 0) {
			int addr = processBasicTuningBits(console, blocks[1], time);

			// Groups 0A & 0B: to extract PS segment we need blocks 1 and 3
			if(blocksOk[3]) {
				char ch1 = RDS.toChar( (blocks[3]>>8) & 0xFF);
				char ch2 = RDS.toChar(blocks[3] & 0xFF);
				console.print("PS pos=" + addr + ": \"" + ch1 + ch2 + "\" ");
				workingStation.getPS().setChars(addr, ch1, ch2);
			}

			// Groups 0A: to extract AFs we need blocks 1 and 2
			if(version == 0 && blocksOk[2]) {
				//console.printf("Raw AF: %d %d", (blocks[2]>>8) & 0xFF, blocks[2] & 0xFF);
				console.print(workingStation.addAFPair((blocks[2]>>8) & 0xFF, blocks[2] & 0xFF));
			}

			serviceStat.add(ServiceStat.PROG_TYPE, 3);	// TA, M/S, DI
			serviceStat.add(ServiceStat.NAME, 2+16);	// address + characters
			if(version == 0) serviceStat.add(ServiceStat.AF, 16);
		}

		// Group 1A: to extract RP info we need only block 1
		if(type == 1 && version == 0) {
			int tngd = (blocks[1]>>2) & 7;   // transmitter network group designator
			int bsi = (blocks[1]) & 3;       // battery saving interval sync and id
			console.print("RP Config: [" + RP_TNGD_VALUES[tngd]);
			if(tngd > 0) {   // print the rest only if there IS RP
				Application app = workingStation.getApplicationForGroup(7, 0);
				if(app == null) {
					newApp = app = new Paging(workingStation, RP_TNGD_VALUES[tngd]);

					workingStation.setApplicationForGroup(7, 0, app);
				} else if(!(app instanceof Paging)) {
					console.print("Error: this group indicates the presence of paging, while group 7A is used for '" + app.getName() + "'!");
				}

				console.print(", " + ((Paging)app).syncInfo((bsi >> 1) & 1, bsi & 1));
			}
			console.print("], ");

			serviceStat.add(ServiceStat.OVERHEAD, 16);
		}

		if(type==1) {
			serviceStat.add(ServiceStat.PIN, 16);
			serviceStat.add(ServiceStat.OVERHEAD, 5);
		}

		// Groups 1A & 1B: to extract PIN we need blocks 1 and 3
		if(type == 1 && blocksOk[3]) {
			int pin = blocks[3];
			boolean pinValid = workingStation.setPIN(pin);
			// Radio Paging section 3.2.4.3: if day=0 in the PIN, the PIN is invalid
			// and the field is used to transmit enhanced paging info instead.
			if(pinValid) {
				console.printf("PIN=%04X [%s] ", pin, station.getPINText());
			} else {
				int variant = (blocks[3]>>8) & 0xF;
				switch(variant) {
				case 0: case 1: case 2: case 3:
					int opc = blocks[3] & 0xF;
					printOPC(console, opc);
					if(opc != 0) {
						int pac = (blocks[3] >> 4) & 0x3F;
						printPAC(console, pac);
					}
					break;
				case 4:
					int ecc = blocks[3] & 0xFF;
					workingStation.setECC(ecc);
					printECC(console, station.getPI(), ecc);
					break;
				default:
					console.printf("<Variant %d not implemented> ", variant);
				}
			}
		}

		// Group 1A: to extract slow labeling codes, we need blocks 1 and 2
		if(type == 1 && version == 0 && blocksOk[2]) {
			int variant = (blocks[2] >> 12) & 0x7;
			int la = (blocks[2] >> 15) & 0x1;
			console.print("LA=" + la + " v=" + variant + " ");
			switch(variant) {
			case 0:
			{
				int ecc = blocks[2] & 0xFF;
				workingStation.setECC(ecc);
				printECC(console, station.getPI(), ecc);
				int opc = (blocks[2] >> 8) & 0xF;
				printOPC(console, opc);
				break;
			}

			case 1:
				int tmcid = blocks[2] & 0xFFF;
				console.printf("TMC (old way) ID=0x%03X / (dec)%d", tmcid, tmcid);

				// connect 8A groups with the TMC application
				Application app = workingStation.getApplicationForGroup(8, 0);
				if(app == null) {
					Application appTMC = new AlertC();
					workingStation.setApplicationForGroup(8, 0, appTMC);
					appTMC.setStation(workingStation);
				} else if(!(app instanceof AlertC)) {
					console.print("Error: this group indicates the presence of TMC, while group 8A is used for '" + app.getName() + "'!");
				}
				break;

			case 2:
			{
				int opc = (blocks[2] >> 8) & 0xF;
				printOPC(console, opc);
				if(opc != 0) {
					int pac = blocks[2] & 0x3F;
					printPAC(console, pac);
				}
				break;
			}

			case 3:
				int langID = blocks[2] & 0xFF;
				workingStation.setLanguage(langID);
				console.printf("Language: %02X [%s]", langID, 
						langID < RDS.languages.length ? RDS.languages[langID][1] : "");
				break;

			case 6:
				console.printf("Broadcaster data: %03X", blocks[2] & 0xFFF);
				break;

			case 7:
				console.printf("EWS identification: %03X", blocks[2] & 0xFFF);
				break;

			default:
				console.printf("Unhandled data: %03X", blocks[2] & 0xFFF);
			}
		}

		// Groups 2A and 2B: to extract RT characters we need blocks 1 and (2 or 3)
		if(type == 2 && (blocksOk[2] || blocksOk[3])) {
			int addr = blocks[1] & 0xF;
			int ab = (blocks[1]>>4) & 1;

			// First extract the 4 potential characters
			char ch1 = RDS.toChar( (blocks[2]>>8) & 0xFF);
			char ch2 = RDS.toChar(blocks[2] & 0xFF);
			char ch3 = RDS.toChar( (blocks[3]>>8) & 0xFF);
			char ch4 = RDS.toChar(blocks[3] & 0xFF);

			if(!blocksOk[2]) {
				ch1 = ch2 = '?';
			}
			if(!blocksOk[3]) {
				ch3 = ch4 = '?';
			}

			Text rt = workingStation.getRT();

			// Need to handle the case group 2A and both data blocks ok
			// separately, in order to correctly highlight the 4 characters
			// of the latest RT segment received
			if(version == 0 && blocksOk[2] && blocksOk[3]) {
				rt.setChars(addr, ch1, ch2, ch3, ch4);
			} else {
				// Else only one block is provided (type 2B) or valid (type 2A)
				if(blocksOk[2] && version == 0) {
					rt.setChars(addr*2, ch1, ch2);
				}
				if(blocksOk[3]) {
					rt.setChars(version == 0 ? addr*2+1 : addr, ch3, ch4);
				}
			}

			rt.setFlag(ab);

			console.print("RT A/B=" + (ab == 0 ? 'A' : 'B') + " pos=" + addr + ": \"");
			if(version == 0) console.print(ch1 + "" + ch2);
			console.print(ch3 + "" + ch4);
			console.print('\"');
		}

		if(type == 2) serviceStat.add(ServiceStat.RT, version == 0 ? 5+16+16 : 5+16); 

		// Groups 3A: to extract AID we need blocks 1 and 3
		if(type == 3 && version == 0 && blocksOk[3]) {
			int aid = blocks[3];
			int odaG = (blocks[1]>>1) & 0xF;
			int odaV = blocks[1] & 1;

			if(aid == 0) console.print("NO AID: ");
			else console.printf("AID #%04X ", aid);

			// return the ODA
			Application app = workingStation.getApplicationForGroup(odaG, odaV);
			if(app != null) {
				if(!(app instanceof ODA)) {
					console.printf("Currently group assigned to '%s' (non-ODA); it should not be assigned to AID %04X", app.getName(), aid);
				} else if(((ODA)app).getAID() != aid) { 
					console.printf("Current AID for group (%04X) does not match new AID (%04X)", ((ODA)app).getAID(), aid);
					app = null;
				}
			} else {
				app = ODA.forAID(aid);

				workingStation.setODA(aid, blocks[1] & 0x1F, app);

				if(app != null) {
					newApp = app;
					workingStation.setApplicationForGroup(odaG, odaV, app);
					app.setStation(workingStation);
				} else {
					console.print("Unknown AID!");
				}
			}

			if(app != null) {
				console.print("(" + app.getName() + "): ");
			}
			else console.print(" ");

			if(odaG == 0 && odaV == 0) console.print("only in group 3A   ");
			else if(odaG == 0xF && odaV == 1) console.print("temporary data fault at encoder   ");
			else console.print("group " + odaG + (char)('A' + odaV) + "   ");

			// if data ok, pass it to the ODA handler
			if(app != null && blocksOk[2]) {
				console.printf("ODA data=%04X", blocks[2]);

				console.println();
				console.print("\t" + app.getName()  + " --> ");
				app.receiveGroup(console, type, version, blocks, blocksOk, time);
			}
		}
		if(type == 3 && version==0) serviceStat.add(ServiceStat.ODA, 5+16+16);	// TODO refine per ODA

		// Groups 4A: to extract time we need blocks 1, 2 and 3
		if(type == 4 && version == 0 && blocksOk[2] && blocksOk[3]) {
			int mjd = ((blocks[1] & 0x3)<<15) | ((blocks[2] & 0xFFFE)>>1);

			int hour = ((blocks[2] & 1)<<4) | ((blocks[3] & 0xF000)>>12);
			int minute = ((blocks[3]>>6) & 0x3F);
			int sign = (blocks[3] & 0x20) == 0 ? 1 : -1;
			int offset = blocks[3] & 0x1F;

			if(mjd >= 15079) {
				// The formulas below are valid from 1 March 1900 (MJD 15079).
				int yp = (int)((mjd - 15078.2)/365.25);
				int mp = (int)( ( mjd - 14956.1 - (int)(yp * 365.25) ) / 30.6001 );
				int day = mjd - 14956 - (int)( yp * 365.25 ) - (int)( mp * 30.6001 );
				int k = (mp == 14 || mp == 15) ? 1 : 0;
				int year = 1900 + yp + k;
				int month = mp - 1 - k * 12;

				/* Time is in UTC, hence set the initial time zone to UTC (offset 0), set date values
				 * and call getDate() once to force internal calculation with date values interpreted
				 * as UTC. Then set the actual time zone and obtain time via getDate().
				 * Without the first call to getDate(), the internal calculation would not happen until
				 * the time zone is changed, resulting in incorrect interpretation of date/time values.
				 */
				SimpleTimeZone tz = new SimpleTimeZone(sign * offset * 30 * 60 * 1000, "");
				Calendar cal = new GregorianCalendar(new SimpleTimeZone(0, ""));
				cal.clear();
				cal.set(year, month-1, day, hour, minute);
				cal.getTime();
				cal.setTimeZone(tz);
				Date date = cal.getTime();

				String datetime = String.format("%02d:%02d%c%dmin %04d-%02d-%02d", 
						hour, minute, sign>0 ? '+' : '-', offset*30, year, month, day);
				console.printf("CT " + datetime);
				workingStation.setTimeZone(tz);
				workingStation.setDate(date, datetime, time);
				log.addMessage(new ClockTime(time, date));
			} else {
				// Ignore earlier dates.
				console.printf("CT invalid");
			}
		}
		if(type == 4 && version == 0) {
			// is there paging ?
			Application app = workingStation.getApplicationForGroup(7, 0);
			if(app != null && app instanceof Paging) {
				// then the 4A group act as 1A - start of interval
				console.print(", [RT: " + ((Paging)app).fullMinute() + "]");
			}

			serviceStat.add(ServiceStat.CT, 2+16+16);
			serviceStat.add(ServiceStat.OVERHEAD, 3);
		}

		// Groups 5A-9A, 11A-13A: TDC, we need blocks 1, 2 and 3
		// but don't handle 7A groups here if using RP
		if(((type >= 5 && type <= 9) || (type >= 11 && type <= 13)) && version == 0) {
			int a = (blocks[1] & 0x1F);

			switch(type) {
			case 5: console.print("TDC/ODA "); break;
			case 6: console.print("IH/ODA "); break;
			case 7: console.print("RP/ODA "); break;
			case 8: console.print("TMC/ODA "); break;
			case 9: console.print("EWS/ODA "); break;
			case 11: console.print("ODA "); break;
			case 12: console.print("ODA "); break;
			case 13: console.print("ERP/ODA "); break;
			}

			if(blocksOk[2] && blocksOk[3]) {
				console.printf("%02X/%04X-%04X", a, blocks[2], blocks[3]);
				if(type == 5 || type == 6) {
					console.printf(" (%c%c%c%c)", 
							RDS.toChar((blocks[2]>>8) & 0xFF), RDS.toChar(blocks[2] & 0xFF), 
							RDS.toChar((blocks[3]>>8) & 0xFF), RDS.toChar(blocks[3] & 0xFF));
				}
			}

			Application app = workingStation.getApplicationForGroup(type, version);

			if(app == null) {
				if(type == 5) {
					newApp = TDC.createPreferredTDCApp();
					workingStation.setApplicationForGroup(5, 0, newApp);
				} else if(type == 6) {
					newApp = new InHouse();
					workingStation.setApplicationForGroup(6, 0, newApp);
				}

			}

			if(app != null) {
				console.println();
				console.print("\t" + app.getName() +  " --> ");
				app.receiveGroup(console, type, version, blocks, blocksOk, time);

				serviceStat.add(app.getName(), 5+16+16);
			} else {
				serviceStat.add(ServiceStat.WASTE, 5+16+16);
			}
		}



		// Groups 10A: PTYN, we need blocks 1, 2 and 3
		if(type == 10 && version == 0 && blocksOk[1]) {
			int ab = (blocks[1] >> 4) & 1;
			int pos = blocks[1] & 1;

			console.print("PTYN, flag=" + (char)('A' + ab) + ", pos=" + pos + ": \"");

			if(blocksOk[2]) {
				char c1 = RDS.toChar((blocks[2]>>8) & 0xFF);
				char c2 = RDS.toChar(blocks[2] & 0xFF);
				workingStation.getPTYN().setChars(pos*2, c1, c2);
				console.print(Character.toString(c1) + Character.toString(c2));
			} else console.print("??");

			if(blocksOk[3]) {
				char c1 = RDS.toChar((blocks[3]>>8) & 0xFF);
				char c2 = RDS.toChar(blocks[3] & 0xFF);
				workingStation.getPTYN().setChars(pos*2+1, c1, c2);
				console.print(Character.toString(c1) + Character.toString(c2));
			} else console.print("??");

			console.print("\"");

			serviceStat.add(ServiceStat.PTYN, 2+16+16);
			serviceStat.add(ServiceStat.OVERHEAD, 3);
		}

		// Groups 14: to extract variant we need only block 1
		if(type == 14) {
			Station on = null;
			console.print("EON, ");

			// in both versions if we have block 3 we have ON PI
			int onPI = -1;
			if(blocksOk[3]) {
				onPI = blocks[3];
				console.printf("ON.PI=%04X%s, ", onPI, onPI == workingStation.getPI() ? " (self)" : "");

				if(onPI != workingStation.getPI()) {
					on = workingStation.getON(onPI);
					if(on == null) {
						on = new OtherNetwork(onPI);
						workingStation.addON(on);
					}
				} else { 
					// ON.PI may be equal to TN.PI in case of variant 12: it
					// is used to transmit linkage information for the
					// transmitting network. In this case we must surely not
					// create a new OtherNetwork instance.
					on = workingStation;
				}
			}

			int ontp = (blocks[1]>>4) & 1;
			if(on != null) on.setTP(ontp == 1);
			console.print("ON.TP=" + ontp + ", ");

			if(version == 0) { // info about ON only in 14A groups
				int variant = blocks[1] & 0xF; 
				console.print("v=" + variant + ", ");

				// to extract ON info we need block 2
				if(blocksOk[2]) {
					if(variant >= 0 && variant <= 3) {  // ON PS
						char ch1 = RDS.toChar( (blocks[2]>>8) & 0xFF);
						char ch2 = RDS.toChar( blocks[2] & 0xFF);
						console.print("ON.PS pos=" + variant + ": \"" + ch1 + ch2 + "\", ");

						if(on != null) on.getPS().setChars(variant, ch1, ch2);
					}

					if(variant == 4) { // frequencies
						if(on != null) {
							console.print("ON.AF: " + on.addAFPair((blocks[2]>>8)&0xFF, blocks[2]&0xFF) + " ");
						}
					}

					if(variant >= 5 && variant <= 8) {
						if(on != null) {
							console.print("ON.AF: " + on.addMappedFreq((blocks[2]>>8) & 0xFF, blocks[2] & 0xFF));
						}
					}

					if(variant == 12) {
						console.printf("Linkage information: %04X ", blocks[2]);
					}

					if(variant == 13) {
						int onpty = (blocks[2]>>11) & 0x1F;
						int onta = (blocks[2]) & 1;
						console.printf("ON.PTY=%d, ON.TA=%d ", onpty, onta);
						if(on != null) {
							on.setPTY(onpty);
							on.setTA(onta == 1);
						}
					}

					if(variant == 14) {
						int onpin = blocks[2];
						console.printf("ON.PIN=%04X ", onpin);
						if(on != null) {
							on.setPIN(onpin);
							console.print("[" + on.getPINText() + "]");
						}
					}
				}
				serviceStat.add(ServiceStat.ON, 5+16+16);
			} else { // 14B groups
				int onta = (blocks[1]>>3) & 1;
				String message = (onta==1 ? "Switch now to ON" : "Switch back from ON");
				console.print("ON.TA=" + onta + ", " + message);
				if(onta == 1) {
					log.addMessage(new EONSwitch(time, on));
				} else {
					log.addMessage(new EONReturn(time, on));
				}
				if(onPI >= 0) message += String.format(": PI=%04X", onPI);
				if(on != null) message += " (" + on.getStationName().trim() + ")";
				if(groupCountSinceEonSwitch > 20) station.addTrafficEvent(time, message);
				groupCountSinceEonSwitch = 0;

				serviceStat.add(ServiceStat.ON, 2+16);
				serviceStat.add(ServiceStat.OVERHEAD, 3);
			}

		}

		// Type 15A: RDS2 Long PS and former RBDS Fast PS (obsolete).
		// They are somewhat compatible, except:
		//  1) Long PS addresses are 3-bit, whereas Fast PS addresses were
		// 1-bit (the other two being marked as "spare bits", so they could
		// theoretically be anything).
		//  2) Long PS uses UTF-8 encoding, whereas Fast PS used the RDS
		// 8-bit charset.
		// However I believe most Fast PS implementations set the spare bits
		// to 0, and use only ASCII characters. So we treat everything as
		// Long PS, and it *should* also work fine for most, if not all,
		// Fast PS uses.
		if(type == 15 && version == 0) {
			int addr = blocks[1] & 7;
			console.print("Long PS, pos=" + addr + ":");
			for(int i=0; i<=1; i++) {
				if(blocksOk[i+2]) {
					byte byte1 = (byte)((blocks[i+2]>>8) & 0xFF);
					byte byte2 = (byte)(blocks[i+2] & 0xFF);
					console.print(String.format(" %02X %02X", byte1, byte2));
					station.getLPS().set(addr*4+i*2, byte1, byte2);
				} else console.print(" -- --");
			}
			console.print(", TA=" + ((blocks[1]>>4) & 1));

			serviceStat.add(ServiceStat.PROG_TYPE, 1);	// TA bit
			serviceStat.add(ServiceStat.NAME, 3+16+16);	// address + 4 characters
			serviceStat.add(ServiceStat.OVERHEAD, 1);	// 1 unused bit
		}

		// For 15B we need only group 1, and possibly group 3
		if(type == 15 && version == 1) {
			processBasicTuningBits(console, blocks[1], time);
			if(blocksOk[3]) processBasicTuningBits(console, blocks[3], time);

			serviceStat.add(ServiceStat.OVERHEAD, 5);	// group type: protocol overhead in group D
			serviceStat.add(ServiceStat.PROG_TYPE, 6);	// PTY+TP: program type in group D
			serviceStat.add(ServiceStat.PROG_TYPE, 2*5);	// TA, MS, DIseg and DI in groups B and D
		}

		// add a log message for each group
		log.addMessage(new GroupReceived(time, blocks, nbOk, consoleWriter.toString()));

		// if the last 3 blocks of a group were received completely, then commit service stats
		if(blocksOk[1] && blocksOk[2] && blocksOk[3]) {
			station.addServiceStat(serviceStat);
		}

		// post log message for app creation only if the group is not being ignored
		if(newApp != null && station == workingStation)
			log.addMessage(new ApplicationChanged(time, null, newApp));

		groupCountSinceEonSwitch++;

	}

	private void printECC(PrintWriter console, int pi, int ecc) {
		console.printf("ECC=%02X ", ecc);
		if(pi != -1) console.print("[" + RDS.getISOCountryCode((pi>>12) & 0xF, ecc) + "] ");
	}

	private void printOPC(PrintWriter console, int opc) {
		if(opc == 0) {
			console.print("No ERP ");
		} else {
			console.printf("ERP OPC=%d ", opc);
		}
	}

	private void printPAC(PrintWriter console, int pac) {
		console.printf("PAC=%d ", pac);
	}

	public TunedStation getTunedStation() {
		return station;
	}

	public void notifyFrequencyChange(RDSTime time) {
		station = new TunedStation(time);
	}


	private final GroupReaderEventVisitor readerEventVisitor = new GroupReaderEventVisitor() {
		@Override
		public void visit(StationChangeEvent stationChangeEvent) {
			if(station != null)
				log.addMessage(new StationLost(station.getTimeOfLastPI(), station));
			RDSTime time = stationChangeEvent.getTime();
			station = new TunedStation(time);
			log.addMessage(new StationTuned(time, station));
		}

		@Override
		public void visit(GroupEvent groupEvent) {
			RDSTime time = groupEvent.getTime();

			// defensive programming: station should not be null...
			// but a (defective) input driver may forget to send the
			// StationChangeEvent...
			if(station == null) {
				station = new TunedStation(time);
				log.addMessage(new StationTuned(time, station));
			}
			// end defensive programming section

			int[] blocks = groupEvent.blocks;

			boolean[] blocksOk = new boolean[4];
			int nbOk = 0;
			for(int i=0; i<4; i++) {
				blocksOk[i] = (blocks[i] >= 0);
				if(blocksOk[i]) nbOk++;
			}

			processGroup(nbOk, blocksOk, blocks, time);
			if(log != null) log.notifyGroup();
		}

		@Override
		public void visit(FrequencyChangeEvent frequencyChangeEvent) {
			//console.println("% Frequency changed: " + frequencyChangeEvent.frequency);
		}
	};

	/**
	 * Processes one group in the group stream available on the given reader.
	 * 
	 * @param reader a group reader
	 * @return {@code false} if the end of the stream has been reached, 
	 * {@code true} otherwise
	 * @throws IOException
	 */
	public void processOneGroup(GroupReaderEvent evt) throws IOException {
		evt.accept(readerEventVisitor);


		/*
		 * TODO FIXME: do we really need this?
			// detect isolated groups with wrong PI
			// (only if block 0 was received correctly...)
			if(blocks[0] != -1) {
				if(nextBlocks[0] != -1 && lastPI == nextBlocks[0] && lastPI != blocks[0]) {
					blocksOk = noneOk;
				} else lastPI = blocks[0];
			}

			// detect isolated groups with wrong PTY/TP
			// (only if block 1 was received correctly...)
			if(blocks[1] != -1) {
				if(nextBlocks[1] != -1 && lastPTY == ((nextBlocks[1]>>5) & 0x3F) && lastPTY != ((blocks[1]>>5) & 0x3F)) {
					blocksOk = noneOk;
				} else lastPTY = ((blocks[1]>>5) & 0x3F);
			}

			// detect spurious B-type groups
			// need to have blocks 0, 1 & 2 to do this
			if(blocks[0] != -1 && blocks[1] != -1 && blocks[2] != -1) { 
				if(((blocks[1]>>11) & 1) == 1) {
					if(blocks[0] != blocks[2]) blocksOk = noneOk;
				}
			}
		 */
	}

	public void reset() {
		station = null;
	}
}
