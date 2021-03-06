package org.nolanlab.codex.upload.scope;

import org.nolanlab.codex.upload.legacy.ExperimentView;
import org.nolanlab.codex.upload.gui.NewGUI;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 *
 * @author Vishal
 */
public class Zeiss implements Microscope  {

    public void guessZSlices(File dir, NewGUI gui) {
        if(dir != null) {
            for (File cyc : dir.listFiles()) {
                if (cyc != null && cyc.isDirectory()) {
                    File[] cycFiles = cyc.listFiles();
                    Arrays.sort(cycFiles, Collections.reverseOrder());
                    for (File tif : cycFiles) {
                        if (tif != null && !tif.isDirectory() && tif.getName().endsWith(".tif")) {
                            int lastZIndex = tif.getName().lastIndexOf("z");
                            String zNumber = tif.getName().substring(lastZIndex+1, lastZIndex+3);
                            if (zNumber != null) {
                                int zIndex = Integer.parseInt(zNumber);
                                zNumber = String.valueOf(zIndex);
                            }
                            gui.getNumPlanesField().setText(zNumber);
                            break;
                        }
                    }
                    //break outer loop
                    break;
                }
            }
        }
    }

    public void guessChannelNamesAndWavelength(File dir, NewGUI gui) {
        if(dir != null) {
            for (File cyc : dir.listFiles()) {
                if (cyc != null && cyc.isDirectory()) {
                    File[] cycFiles = cyc.listFiles();
                    Arrays.sort(cycFiles, Collections.reverseOrder());
                    LinkedHashMap<String, Boolean> chVsBool = new LinkedHashMap<String, Boolean>();
                    chVsBool.put("CH1", false);
                    chVsBool.put("CH2", false);
                    chVsBool.put("CH3", false);
                    chVsBool.put("CH4", false);
                    for (File tif : cycFiles) {
                        if (tif != null && !tif.isDirectory() && tif.getName().endsWith(".tif")) {
                            int lastCIndex = tif.getName().lastIndexOf("c");
                            String chNumber = tif.getName().substring(lastCIndex+1, lastCIndex+2);
                            if (chNumber != null) {
                                if(chVsBool.containsKey("CH"+chNumber)){
                                    chVsBool.put("CH"+chNumber, true);
                                }
                            }
                        }
                    }
                    LinkedHashMap<String, String> chVsWavelength = new LinkedHashMap<String, String>();
                    chVsWavelength.put("CH1","425");
                    chVsWavelength.put("CH2","525");
                    chVsWavelength.put("CH3","595");
                    chVsWavelength.put("CH4","670");

                    String ch="";
                    String waveL="";

                    boolean first = true;
                    for (String key: chVsBool.keySet()) {
                        if (!first && chVsBool.get(key)) {
                            ch += ";"+key;
                            waveL += ";"+chVsWavelength.get(key);
                        }
                        else {
                            if(chVsBool.get(key)) {
                                first = false;
                                ch += key;
                                waveL += chVsWavelength.get(key);
                            }
                        }
                    }
                    gui.getNumChannelsField().setText(String.valueOf(ch.split(";").length));
                    gui.getChannelNamesField().setText(ch);
                    gui.getWavelengthsField().setText(waveL);
                    //break outer loop
                    break;
                }
            }
        }
    }

    /*
    Set the number of cyles/range field depending upon the content of Experiment folder.
    */
    public void guessCycleRange(File dir, NewGUI gui) {
        int lowL = 1;
        int upL = getMaxCycNumberFromFolder(dir);
        if(upL == 0) {
            gui.getCycleRangeField().setText(String.valueOf(lowL));
        }
        else if(lowL == upL) {
            gui.getCycleRangeField().setText(String.valueOf(lowL));
        }
        else {
            gui.getCycleRangeField().setText(String.valueOf(lowL) + "-" + String.valueOf(upL));
        }
    }


    /*
    Method to find the total number of Cycle folders present in the experiment directory.
    */
    public int getMaxCycNumberFromFolder(File dir) {
        ArrayList<Integer> cycNumbers = new ArrayList<Integer>();
        if (dir != null) {
            for (File cyc : dir.listFiles()) {
                if (cyc != null && cyc.isDirectory()) {
                    String cycFolderName = cyc.getName();
                    String[] cycVal = cycFolderName.split("-");
                    cycNumbers.add(Integer.parseInt(cycVal[cycVal.length-1].replaceAll("[^0-9]", "")));
                }
            }
        }
        Collections.sort(cycNumbers, Collections.reverseOrder());
        return cycNumbers == null || cycNumbers.isEmpty() ? 0 : cycNumbers.get(0);
    }

    /*
    Method to set region height and region width to 1 when TMA data is loaded
    */
    public void guessWidthAndHeight(File dir, NewGUI gui) {
        if(gui.isTMA()) {
            gui.getRegionWidthField().setText("1");
            gui.getRegionHeightField().setText("1");
        }
    }

    public boolean isTilesAProductOfRegionXAndY(File dir, NewGUI gui) {
        if (dir != null) {
            for (File cyc : dir.listFiles()) {
                if (cyc != null && cyc.isDirectory()) {
                    File[] cycFiles = cyc.listFiles();
                    Arrays.sort(cycFiles, Collections.reverseOrder());
                    for (File tif : cycFiles) {
                        if (tif != null && !tif.isDirectory() && tif.getName().endsWith(".tif")) {
                            int last_Index = tif.getName().lastIndexOf("_");
                            String regXYNumber = tif.getName().substring(last_Index - 2, last_Index);
                            if (regXYNumber != null) {
                                int regXYIndex = Integer.parseInt(regXYNumber);
                                if (regXYIndex == Integer.parseInt(gui.getRegionWidthField().getText()) * Integer.parseInt(gui.getRegionHeightField().getText())) {
                                    return true;
                                } else {
                                    return false;
                                }
                            }
                            break;
                        }
                    }
                    //break outer loop
                    break;
                }
            }
        }
        return false;
    }

    /*
    Set the tile overlap
    */
    public void guessTileOverlap(NewGUI gui) {
        gui.getTileOverlapXField().setText("10");
        gui.getTileOverlapYField().setText("10");
    }

    // Legacy code begins
    public void guessZSlices(File dir, ExperimentView experimentView) {
        if(dir != null) {
            for (File cyc : dir.listFiles()) {
                if (cyc != null && cyc.isDirectory()) {
                    File[] cycFiles = cyc.listFiles();
                    Arrays.sort(cycFiles, Collections.reverseOrder());
                    for (File tif : cycFiles) {
                        if (tif != null && !tif.isDirectory() && tif.getName().endsWith(".tif")) {
                            int lastZIndex = tif.getName().lastIndexOf("z");
                            String zNumber = tif.getName().substring(lastZIndex+1, lastZIndex+3);
                            if (zNumber != null) {
                                int zIndex = Integer.parseInt(zNumber);
                                zNumber = String.valueOf(zIndex);
                            }
                            experimentView.getVal9().setText(zNumber);
                            break;
                        }
                    }
                    //break outer loop
                    break;
                }
            }
        }
    }

    public void guessChannelNamesAndWavelength(File dir, ExperimentView experimentView) {
        if(dir != null) {
            for (File cyc : dir.listFiles()) {
                if (cyc != null && cyc.isDirectory()) {
                    File[] cycFiles = cyc.listFiles();
                    Arrays.sort(cycFiles, Collections.reverseOrder());
                    LinkedHashMap<String, Boolean> chVsBool = new LinkedHashMap<String, Boolean>();
                    chVsBool.put("CH1", false);
                    chVsBool.put("CH2", false);
                    chVsBool.put("CH3", false);
                    chVsBool.put("CH4", false);
                    for (File tif : cycFiles) {
                        if (tif != null && !tif.isDirectory() && tif.getName().endsWith(".tif")) {
                            int lastCIndex = tif.getName().lastIndexOf("c");
                            String chNumber = tif.getName().substring(lastCIndex+1, lastCIndex+2);
                            if (chNumber != null) {
                                if(chVsBool.containsKey("CH"+chNumber)){
                                    chVsBool.put("CH"+chNumber, true);
                                }
                            }
                        }
                    }
                    LinkedHashMap<String, String> chVsWavelength = new LinkedHashMap<String, String>();
                    chVsWavelength.put("CH1","425");
                    chVsWavelength.put("CH2","525");
                    chVsWavelength.put("CH3","595");
                    chVsWavelength.put("CH4","670");

                    String ch="";
                    String waveL="";

                    boolean first = true;
                    for (String key: chVsBool.keySet()) {
                        if (!first && chVsBool.get(key)) {
                            ch += ";"+key;
                            waveL += ";"+chVsWavelength.get(key);
                        }
                        else {
                            if(chVsBool.get(key)) {
                                first = false;
                                ch += key;
                                waveL += chVsWavelength.get(key);
                            }
                        }
                    }
                    experimentView.getVal11().setText(ch);
                    experimentView.getVal21().setText(waveL);
                    //break outer loop
                    break;
                }
            }
        }
    }

    public void guessCycleRange(File dir, ExperimentView experimentView) {
        int lowL = 1;
        int upL = getMaxCycNumberFromFolder(dir);
        if(upL == 0) {
            experimentView.getVal13().setText(String.valueOf(lowL));
        }
        else if(lowL == upL) {
            experimentView.getVal13().setText(String.valueOf(lowL));
        }
        else {
            experimentView.getVal13().setText(String.valueOf(lowL) + "-" + String.valueOf(upL));
        }
    }

    public boolean isTilesAProductOfRegionXAndY(File dir, ExperimentView experimentView) {
        if (dir != null) {
            for (File cyc : dir.listFiles()) {
                if (cyc != null && cyc.isDirectory()) {
                    File[] cycFiles = cyc.listFiles();
                    Arrays.sort(cycFiles, Collections.reverseOrder());
                    for (File tif : cycFiles) {
                        if (tif != null && !tif.isDirectory() && tif.getName().endsWith(".tif")) {
                            int last_Index = tif.getName().lastIndexOf("_");
                            String regXYNumber = tif.getName().substring(last_Index - 2, last_Index);
                            if (regXYNumber != null) {
                                int regXYIndex = Integer.parseInt(regXYNumber);
                                if (regXYIndex == Integer.parseInt(experimentView.getVal17().getText()) * Integer.parseInt(experimentView.getVal18().getText())) {
                                    return true;
                                } else {
                                    return false;
                                }
                            }
                            break;
                        }
                    }
                    //break outer loop
                    break;
                }
            }
        }
        return false;
    }
}
