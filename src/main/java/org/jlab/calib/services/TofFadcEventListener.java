package org.jlab.calib.services;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JOptionPane;

import org.jlab.calib.services.ctof.CTOFCalibrationEngine;
import org.jlab.detector.calib.utils.CalibrationConstants;
import org.jlab.detector.calib.utils.DatabaseConstantProvider;
import org.jlab.groot.data.GraphErrors;
import org.jlab.groot.data.H1F;
import org.jlab.groot.data.H2F;
import org.jlab.groot.fitter.DataFitter;
import org.jlab.groot.graphics.EmbeddedCanvas;
import org.jlab.groot.group.DataGroup;
//import org.jlab.calib.temp.DataGroup;
import org.jlab.groot.math.F1D;
import org.jlab.groot.ui.TCanvas;
import org.jlab.io.base.DataEvent;
import org.jlab.utils.groups.IndexedList;

public class TofFadcEventListener extends TOFCalibrationEngine {

	private String showPlotType = "LEFT";
	
	// indices for constants
	public final int LEFT_OVERRIDE = 0;
	public final int RIGHT_OVERRIDE = 1;
	public final int WIDTH_OVERRIDE = 2;
	
	public double 	MIN_X = -10.0;
	public double	MAX_X = 60.0;
	public double	WIDTH = 10.0;
	
	//private String fitOption = "RQ";	

	public TofFadcEventListener() {

		stepName = "FADC offset";
		histTitle = "FADC";
		fileNamePrefix = "FTOF_CALIB_FADC_";
		// get file name here so that each timer update overwrites it
		filename = nextFileName();
		
		calib = 
				new CalibrationConstants(3,
						"left/F:right/F:width/F");

		calib.setName("/calibration/ftof/fadc_offset");
		calib.setPrecision(2);

		// assign constraints depending on paddle number column
		for (int sector = 1; sector <= 6; sector++) {
			for (int layer=1; layer<=3; layer++) {
				for (int paddle=1; paddle <= NUM_PADDLES[layer-1]; paddle++) {
//					calib.addConstraint(5, expectedRes(sector,layer,paddle)*0.8,
//							expectedRes(sector,layer,paddle)*1.2,
//							3,
//							paddleNumber(sector,layer,paddle));
				}
			}
		}		

	}

	@Override
	public void populatePrevCalib() {

		prevCalRead = true;
	}
	
	@Override
	public void resetEventListener() {

		// perform init processing

		// create the histograms for the first iteration
		createHists();
	}

	public void createHists() {
		
		for (int sector = 1; sector <= 6; sector++) {
			for (int layer = 1; layer <= 3; layer++) {
				int layer_index = layer - 1;
				for (int paddle = 1; paddle <= NUM_PADDLES[layer_index]; paddle++) {

					DataGroup dg = new DataGroup(2,1);

					// create all the histograms and functions
					H1F histL = 
							new H1F("fadcHistLeft","Left " + histTitle(sector, layer, paddle),
									2000, MIN_X, MAX_X);
					histL.setTitleX("TDC time - FADC time (ns)");
					dg.addDataSet(histL, 0);

					H1F histR = 
							new H1F("fadcHistRight","Right " + histTitle(sector, layer, paddle),
									2000, MIN_X, MAX_X);
					histR.setTitleX("TDC time - FADC time (ns)");
					dg.addDataSet(histR, 1);

					dataGroups.add(dg,sector,layer,paddle);    

					// initialize the constants array
					Double[] consts = {UNDEFINED_OVERRIDE, UNDEFINED_OVERRIDE, UNDEFINED_OVERRIDE};
					// override values
					constants.add(consts, sector, layer, paddle);
				}
			}
		}
	}

	@Override
	public void processEvent(DataEvent event) {

		List<TOFPaddle> paddleList = DataProvider.getPaddleList(event);
		processPaddleList(paddleList);

	}

	@Override
	public void processPaddleList(List<TOFPaddle> paddleList) {

		for (TOFPaddle pad : paddleList) {

			int sector = pad.getDescriptor().getSector();
			int layer = pad.getDescriptor().getLayer();
			int component = pad.getDescriptor().getComponent();
			
			if (pad.getADC_TIMEL()>0 && pad.getTDCL()>0) {
				dataGroups.getItem(sector,layer,component).getH1F("fadcHistLeft").fill(
				pad.tdcToTimeL() - pad.getADC_TIMEL());
			}
			
			if (pad.getADC_TIMER()>0 && pad.getTDCR()>0) {
				dataGroups.getItem(sector,layer,component).getH1F("fadcHistRight").fill(
				pad.tdcToTimeR() - pad.getADC_TIMER());
			}
		}
	}  
	

	@Override
	public void customFit(int sector, int layer, int paddle){

		String[] fields = { 
		"Override left offset:", "Override right offset:", "Override width:"};
		TOFCustomFitPanel panel = new TOFCustomFitPanel(fields,sector,layer);

		int result = JOptionPane.showConfirmDialog(null, panel, 
				"Adjust Fit / Override for paddle "+paddle, JOptionPane.OK_CANCEL_OPTION);
		if (result == JOptionPane.OK_OPTION) {

			double overrideLeft = toDouble(panel.textFields[0].getText());
			double overrideRight = toDouble(panel.textFields[1].getText());
			double overrideWidth = toDouble(panel.textFields[2].getText());
			
			int minP = paddle;
			int maxP = paddle;
			int minS = sector;
			int maxS = sector;
			if (panel.applyLevel == panel.APPLY_P) {
				//
			}
			else {
				minP = 1;
				maxP = NUM_PADDLES[layer-1];
			}
			if (panel.applyLevel == panel.APPLY_L) {
				minS = 1;
				maxS = 6;
			}

			for (int s=minS; s<=maxS; s++) {
				for (int p=minP; p<=maxP; p++) {
					// save the override values
					Double[] consts = constants.getItem(s, layer, p);
					consts[LEFT_OVERRIDE] = overrideLeft;
					consts[RIGHT_OVERRIDE] = overrideRight;
					consts[WIDTH_OVERRIDE] = overrideWidth;

					// update the table
					saveRow(s,layer,p);
				}
			}
			calib.fireTableDataChanged();

		}     
	}

	public Double getLeft(int sector, int layer, int paddle) {

		double offset = 0.0;
		double overrideVal = constants.getItem(sector, layer, paddle)[LEFT_OVERRIDE];

		if (overrideVal != UNDEFINED_OVERRIDE) {
			offset = overrideVal;
		}
		else {
			H1F hist = dataGroups.getItem(sector,layer,paddle).getH1F("fadcHistLeft");
			int maxBin = hist.getMaximumBin();
			if (hist.getEntries() != 0) {
				offset = hist.getXaxis().getBinCenter(maxBin);
			}
		}
		return offset;
	}    
	
	public Double getRight(int sector, int layer, int paddle) {

		double offset = 0.0;
		double overrideVal = constants.getItem(sector, layer, paddle)[RIGHT_OVERRIDE];

		if (overrideVal != UNDEFINED_OVERRIDE) {
			offset = overrideVal;
		}
		else {
			H1F hist = dataGroups.getItem(sector,layer,paddle).getH1F("fadcHistRight");
			int maxBin = hist.getMaximumBin();
			if (hist.getEntries() != 0) {
				offset = hist.getXaxis().getBinCenter(maxBin);
			}
		}
		return offset;
	}    	

	public Double getWidth(int sector, int layer, int paddle) {

		double offset = 0.0;
		double overrideVal = constants.getItem(sector, layer, paddle)[WIDTH_OVERRIDE];

		if (overrideVal != UNDEFINED_OVERRIDE) {
			offset = overrideVal;
		}
		else {
			offset = WIDTH;
		}
		return offset;
	}    
	
	@Override
	public void saveRow(int sector, int layer, int paddle) {
		
		calib.setDoubleValue(getLeft(sector,layer,paddle),
				"left", sector, layer, paddle);
		calib.setDoubleValue(getRight(sector,layer,paddle),
				"right", sector, layer, paddle);
		calib.setDoubleValue(getWidth(sector,layer,paddle),
				"width", sector, layer, paddle);

	}
	
	@Override
	public void showPlots(int sector, int layer) {
		
		showPlotType = "LEFT";
		stepName = "FADC Left";
		super.showPlots(sector, layer);
		showPlotType = "RIGHT";
		stepName = "FADC Right";
		super.showPlots(sector, layer);
		
	}

	@Override
	public void drawPlots(int sector, int layer, int paddle, EmbeddedCanvas canvas) {
		
		H1F hist = new H1F();
		if (showPlotType == "LEFT") { 
			hist = dataGroups.getItem(sector,layer,paddle).getH1F("fadcHistLeft");
		}
		else if (showPlotType == "RIGHT") { 
			hist = dataGroups.getItem(sector,layer,paddle).getH1F("fadcHistRight");
		}
		hist.setTitleX("");
		hist.setTitleY("");
		canvas.draw(hist); 
	}
	
	@Override
	public DataGroup getSummary(int sector, int layer) {

		int layer_index = layer-1;
		double[] paddleNumbers = new double[NUM_PADDLES[layer_index]];
		double[] paddleUncs = new double[NUM_PADDLES[layer_index]];
		double[] leftValues = new double[NUM_PADDLES[layer_index]];
		double[] leftUncs = new double[NUM_PADDLES[layer_index]];
		double[] rightValues = new double[NUM_PADDLES[layer_index]];
		double[] rightUncs = new double[NUM_PADDLES[layer_index]];

		for (int p = 1; p <= NUM_PADDLES[layer_index]; p++) {

			paddleNumbers[p - 1] = (double) p;
			paddleUncs[p - 1] = 0.0;
			leftValues[p - 1] = getLeft(sector, layer, p);
			leftUncs[p - 1] = 0.0;
			rightValues[p-1] = getRight(sector,layer,p);
			rightUncs[p-1] = 0.0;
		}

		GraphErrors leftSumm = new GraphErrors("summ", paddleNumbers,
				leftValues, paddleUncs, leftUncs);
		leftSumm.setMarkerSize(MARKER_SIZE);
		leftSumm.setLineThickness(MARKER_LINE_WIDTH);
		leftSumm.setTitleX("Paddle number");
		leftSumm.setTitleY("FADC Offset Left");
		
		GraphErrors rightSumm = new GraphErrors("summ", paddleNumbers,
				rightValues, paddleUncs, rightUncs);
		rightSumm.setMarkerSize(MARKER_SIZE);
		rightSumm.setLineThickness(MARKER_LINE_WIDTH);
		rightSumm.setTitleX("Paddle number");
		rightSumm.setTitleY("FADC Offset Right");

		DataGroup dg = new DataGroup(2,1);
		dg.addDataSet(leftSumm, 0);
		dg.addDataSet(rightSumm, 1);
		return dg;

	}

	
}