/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.nolanlab.codex.upload.driffta;

import ij.process.ImageProcessor;
import org.nolanlab.codex.upload.model.Experiment;
import org.nolanlab.codex.upload.model.ProcessingOptions;
import org.nolanlab.codex.utils.logger;
import org.nolanlab.codex.upload.scope.MicroscopeTypeEnum;
import fiji.stacks.Hyperstack_rearranger;
import ij.*;
import ij.io.FileSaver;
import ij.io.Opener;
import ij.plugin.*;
import ij.process.ImageConverter;
import ij.process.LUT;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SystemUtils;
import org.scijava.util.FileUtils;

import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import org.apache.commons.lang3.StringUtils;

//Those dependenceis are unused but necessary for driftcomp to work
//imagescience-3.0.0.jar, legacy-imglib1-1-5, vecmath-scijava

/**
 *
 * @author Nikolay and Vishal
 */
public class Driffta {

    private static final int version = 12;
    
    private static Deconvolve_mult dec;

    private static final boolean copy = false;
    private static boolean color = false;
    private static HashMap<String, Double> expVsMs = new HashMap<>();

    // Define the path to a local file.
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Exception {

        SessionIdentifierGenerator gen = new SessionIdentifierGenerator();

        //args = new String[]{"H:\\3-7-17_Pfizer_tissue_10_multicycle", "H:\\processed_Pfizer_tissue10_withH&E", "1", "1"};
        Properties config = new Properties();

        config.load(new FileInputStream(System.getProperty("user.home")+File.separator+"config.txt"));

        final String TMP_SSD_DRIVE = config.get("TMP_SSD_DRIVE").toString();
        final String numGPUs = config.get("numGPU").toString();

        try {
            log("Starting drift compensation. Version " + version);
            log("Arguments as seen by the application:");

            for (int i = 0; i < args.length; i++) {
                log(i + ":" + args[i]);
            }

            if (args.length != 4) {
                throw new IllegalArgumentException("Invalid number of parameters. Must be 4.\nUsage: java -jar CODEX.jar Driffta <base_dir> <out_dir> <region=int> <tile=int> \nAll indices are one-based");
            }

            String baseDir = args[0];
            String outDir = args[1];

            final int region = Integer.parseInt(args[2]);
            final int tile = Integer.parseInt(args[3]);

            File expFile = new File(baseDir + File.separator + "Experiment.json");
            if (!expFile.exists()) {
                throw new IllegalStateException("Experiment JSON file not found: " + expFile);
            }

            File propFile = new File(baseDir + File.separator + "processingOptions.json");
            if (!propFile.exists()) {
                throw new IllegalStateException("Processing Options JSON file not found: " + propFile);
            }

            final Experiment exp = Experiment.loadFromJSON(expFile);

            ProcessingOptions po = ProcessingOptions.load(propFile);
            if(po.isExportImgSeq()) {
                log("Image sequence folder structure recognized...");
                outDir += File.separator + "tiles";
            }

            color = exp.channel_arrangement.toLowerCase().trim().equals("color");

            if (!exp.deconvolution.equals("Microvolution")) {
                log("Deconvolution disabled based on Experiment.json");
            }
            final int numDeconvolutionDevices = (!StringUtils.isBlank(numGPUs)? Integer.parseInt(numGPUs) : 0);

            File chNamesFile = new File(baseDir + File.separator + "channelNames.txt");
            ArrayList<String> chNamesAL = new ArrayList<>();
            try {
                BufferedReader br = new BufferedReader(new FileReader(chNamesFile));
                String s = null;
                while ((s = br.readLine()) != null) {
                    chNamesAL.add(s);
                }
                br.close();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            final String[] channelNames = chNamesAL.toArray(new String[chNamesAL.size()]);

            int[][] deconvIterations = new int[exp.num_cycles][exp.channel_names.length];

            for (int i = 0; i < deconvIterations.length; i++) {
                for (int j = 0; j < deconvIterations[i].length; j++) {
                    if (i == 0 || j != exp.drift_comp_channel - 1) {
                        if (i == exp.num_cycles - 1 && exp.HandEstain) {
                            continue;
                        }
                        deconvIterations[i][j] = exp.deconvolutionIterations;
                    }
                }
            }

            String tmpDestDir = TMP_SSD_DRIVE + File.separator + gen.nextSessionId();

            File f = new File(tmpDestDir);
            f.mkdirs();

            ImagePlus imp = null;

            log("Opening files");
            final ImagePlus[] stack = new ImagePlus[exp.num_cycles * exp.num_z_planes * exp.channel_names.length];
            ExecutorService es = Executors.newWorkStealingPool(Math.min(32, Runtime.getRuntime().availableProcessors() * 2));

            HashSet<Callable<String>> alR = new HashSet<>();

            for (int cycle = exp.cycle_lower_limit; cycle <= exp.cycle_upper_limit; cycle++) {
                final int cycF = cycle;
                final String sourceDir;
                if(!exp.isTMA) {
                    sourceDir = baseDir + File.separator + exp.getDirName(cycle, region, baseDir, false, 0);
                } else {
                    sourceDir = baseDir + File.separator + exp.getDirName(cycle, 1, baseDir, true, region);
                }
                for (int chIdx = 0; chIdx < exp.channel_names.length; chIdx++) {
                    final int chIdxF = chIdx;
                    final String ch = exp.channel_names[chIdx];
                    for (int zSlice = 1; zSlice <= exp.num_z_planes; zSlice++) {
                        final int lz = zSlice;
                        final String sourceFileName = sourceDir + File.separator + exp.getSourceFileName(sourceDir, exp.microscope, tile, zSlice, chIdxF);
                        final int idx = ((exp.num_z_planes * exp.channel_names.length) * (cycle - exp.cycle_lower_limit)) + (exp.channel_names.length * (zSlice - 1)) + chIdx;
                        //log("Creating file opening job for cycle="+cycle+", chIdx="+chIdx + ", zSlice="+zSlice);

                        if (!new File(sourceFileName).exists()) {
                            if (!exp.getDirName(cycF, region, baseDir, exp.isTMA, 0).startsWith("HandE")) {
                                log("Source file does not exist: " + sourceFileName);
                            }
                            continue;
                        }

                        final String destFileName = tmpDestDir + File.separator + "Cyc" + cycle + "_reg" + region + "_" + exp.getSourceFileName(sourceDir, exp.microscope, tile, zSlice, chIdxF);

                        final String cmd = "./lib/tiffcp -c none \"" + sourceFileName + "\" \"" + destFileName + "\"";
                        final String cmdLinux = "tiffcp -c none \"" + sourceFileName + "\" \"" + destFileName + "\"";

                        if (new File(destFileName).exists()) {
                            if (new File(destFileName).length() > 10000) {
                                //log("File already exists, skipping: " + destFileName);
                                continue;
                            }
                        }

                        if (!MicroscopeTypeEnum.KEYENCE.equals(exp.microscope)) {
                            alR.add(() -> {
                                Opener o = new Opener();
                                stack[idx] = o.openImage(sourceFileName);

                                if (stack[idx] != null) {
                                    if (color || stack[idx].getStack().getSize() != 1) {
                                        if (!exp.getDirName(cycF, region, baseDir, exp.isTMA, 0).startsWith("HandE") ||
                                                !exp.getDirName(cycF, 1, baseDir, exp.isTMA, region).startsWith("HandE")) {
                                            ZProjector zp = new ZProjector();
                                            log("Flattening " + stack[idx].getTitle() + ", nslices" + stack[idx].getNSlices() + "ch=" + stack[idx].getNChannels() + "stacksize=" + stack[idx].getStack().getSize());
                                            zp.setImage(stack[idx]);
                                            zp.setMethod(ZProjector.MAX_METHOD);
                                            zp.doProjection();
                                            stack[idx] = zp.getProjection();
                                        }
                                    }
                                    //stack[idx].setTitle(channelNames[(cycF - 1) * exp.channel_names.length + chIdxF]);
                                    return "Image opened: " + destFileName;
                                } else {
                                    return "Image opening failed: " + sourceFileName;
                                }
                            });
                        } else {
                            alR.add(() -> {
                                //log("Opening file: " + sourceFileName);
                                File f1 = new File(destFileName);
                                do {
                                    Process p = null;
                                    if (SystemUtils.IS_OS_WINDOWS) {
                                        p = Runtime.getRuntime().exec(cmd);
                                    } else if (SystemUtils.IS_OS_LINUX) {
                                        log("Source: " + sourceFileName);
                                        log("Destination: " + destFileName);
                                        p = Runtime.getRuntime().exec(cmdLinux);
                                        //log("Ran well");
                                    }
                                    if (p != null) {
                                        p.waitFor();
                                        //log(p.getOutputStream().toString());
                                        //log(p.getErrorStream().toString());
                                        //log("Ran here also");
                                    }
                                    if (!f1.exists()) {
                                        log("Copy process finished but the dest file does not exist: " + destFileName + " trying again.");
                                    }
                                } while (!f1.exists());

                                Opener o = new Opener();
                                stack[idx] = o.openImage(destFileName);
                                new File(destFileName).deleteOnExit();

                                if (stack[idx] != null) {
                                    if (color || stack[idx].getStack().getSize() != 1) {
                                        if (!exp.getDirName(cycF, region, baseDir, exp.isTMA, 0).startsWith("HandE") ||
                                                !exp.getDirName(cycF, 1, baseDir, exp.isTMA, region).startsWith("HandE")) {
                                            ZProjector zp = new ZProjector();
                                            log("Flattening " + stack[idx].getTitle() + ", nslices" + stack[idx].getNSlices() + "ch=" + stack[idx].getNChannels() + "stacksize=" + stack[idx].getStack().getSize());
                                            zp.setImage(stack[idx]);
                                            zp.setMethod(ZProjector.MAX_METHOD);
                                            zp.doProjection();
                                            stack[idx] = zp.getProjection();
                                        }
                                    }
                                    //stack[idx].setTitle(channelNames[(cycF - 1) * exp.channel_names.length + chIdxF]);
                                    return "Image opened: " + destFileName;
                                } else {
                                    return "Image opening failed: " + sourceFileName;
                                }
                            });
                        }
                    }
                }
            }

            log("Submitting file opening jobs");
            log("Working on it...");

            List<Future<String>> lst = es.invokeAll(alR);
            log("All file opening jobs submitted");

            for (Future<String> future : lst) {
                future.get();
            }

            es.shutdown();
            es.awaitTermination(100000, TimeUnit.HOURS);

            log("All files opened. Deleting temporary dir");

            new Thread(() -> FileUtils.deleteRecursively(new File(tmpDestDir))).start();

            if (exp.HandEstain) {
                int cycle = exp.num_cycles;
                for (int zSlice = 1; zSlice <= exp.num_z_planes; zSlice++) {
                    int ch = -1;
                    for (int chIdx = 0; chIdx < exp.channel_names.length; chIdx++) {
                        int idx = ((exp.num_z_planes * exp.channel_names.length) * (cycle - 1)) + (exp.channel_names.length * (zSlice - 1)) + chIdx;
                        if (stack[idx] != null) {
                            ch = chIdx;
                        }
                    }
                    if (ch == -1) {
                        throw new IllegalStateException("H&E image slice is absent for z=" + zSlice);
                    }

                    int idx = ((exp.num_z_planes * exp.channel_names.length) * (cycle - 1)) + (exp.channel_names.length * (zSlice - 1)) + ch;

                    ImagePlus he = stack[idx];

                    if (he.getBitDepth() != 24) {
                        throw new IllegalStateException("Expected a 24-bit RGB image");
                    }

                    //ImagePlus he_R = he.getImageStack();
                    int numCh = exp.channel_names.length;

                    String[] colorNames = new String[]{"R", "G", "B"};

                    ImageConverter ic = new ImageConverter(he);
                    ImageConverter.setDoScaling(false);
                    ic.convertToRGBStack();
                    ic.convertToGray16();

                    int k = 1;
                    for (int i = 1; i < numCh; i++) {
                        idx = ((exp.num_z_planes * exp.channel_names.length) * (cycle - 1)) + (exp.channel_names.length * (zSlice - 1)) + i;
                        ImagePlus he_S = new ImagePlus("HandE_" + colorNames[i - 1], he.getStack().getProcessor(k++).duplicate());
                        stack[idx] = he_S;
//                        stack[idx].getProcessor().multiply(250);
                    }
                    int driftCh = exp.drift_comp_channel;
                    ImagePlus he_R = new ImagePlus("HandE_R_inv", he.getStack().getProcessor(1).duplicate());
                    he_R.getProcessor().invert();
                    idx = ((exp.num_z_planes * exp.channel_names.length) * (cycle - 1)) + (exp.channel_names.length * (zSlice - 1)) + (driftCh - 1);
                    stack[idx] = he_R;
                }
            }
            imp = new Concatenator().concatenate(stack, false);

            final double XYres = exp.per_pixel_XY_resolution;
            final double Zpitch = exp.z_pitch;

            final int[] wavelenghths = exp.emission_wavelengths;
            //Alt impl

            ImagePlus hyp = HyperStackConverter.toHyperStack(imp, exp.channel_names.length, exp.num_z_planes, exp.num_cycles, "xyczt", "composite");

            imp = null;

            //run best focus
            Duplicator dup = new Duplicator();
            //log("Value of hyp: " + hyp);
            int[] bestFocusPlanes = new int[hyp.getNFrames()];

            ImagePlus rp = dup.run(hyp, exp.best_focus_channel, exp.best_focus_channel, 1, hyp.getNSlices(), exp.bestFocusReferenceCycle-exp.cycle_lower_limit+1,  exp.bestFocusReferenceCycle-exp.cycle_lower_limit+1);
            int refZ = Math.max(1,BestFocus.findBestFocusStackFromSingleTimepoint(rp, 1, exp.optionalFocusFragment));
            //Add offset here
            refZ = refZ + exp.focusing_offset;
            Arrays.fill(bestFocusPlanes, refZ);
            //log("The bestZ plane: "+ refZ);

            log("Drift compensation");
            log("Waiting for driftcomp interlock");
            DriftcompInterlockDispatcher.gainLock();
            log("Interlock acquired");
            Driftcomp.compensateDrift(hyp, exp.drift_comp_channel - 1, exp.driftCompReferenceCycle - 1);

            DriftcompInterlockDispatcher.releaseLock();

            int Y = Integer.parseInt(Experiment.getDestStackFileName(exp.tiling_mode, tile, region, exp.region_width).split("Y")[1].split("\\.")[0]);
            int X = Integer.parseInt(Experiment.getDestStackFileName(exp.tiling_mode, tile, region, exp.region_width).split("X")[1].split("_")[0]);

            if (po.isUseBleachMinimizingCrop()) {
                log("Cropping with Bleach minimizing enabled...");
                hyp = (Y % 2 == 1) ? new ImagePlus(hyp.getTitle(), hyp.getImageStack().crop(X == 1 ? 0 : exp.tile_overlap_X, Y == 1 ? 0 : exp.tile_overlap_Y, 0, hyp.getWidth() - (X == 1 ? 0 : exp.tile_overlap_X), hyp.getHeight() - (Y == 1 ? 0 : exp.tile_overlap_Y), hyp.getStackSize()))
                        : new ImagePlus(hyp.getTitle(), hyp.getImageStack().crop(0, exp.tile_overlap_Y, 0, hyp.getWidth() - (X == exp.region_width ? 0 : exp.tile_overlap_X), hyp.getHeight() - exp.tile_overlap_Y, hyp.getStackSize()));
            } else {
                log("Cropping");
                hyp = new ImagePlus(hyp.getTitle(), hyp.getImageStack().crop((int) Math.floor(exp.tile_overlap_X / 2), (int) Math.floor(exp.tile_overlap_Y / 2), 0, hyp.getWidth() - (int) Math.ceil(exp.tile_overlap_X), hyp.getHeight() - (int) Math.ceil(exp.tile_overlap_Y), hyp.getStackSize()));
            }

            hyp = HyperStackConverter.toHyperStack(hyp, exp.channel_names.length, exp.num_z_planes, exp.num_cycles, "xyczt", "composite");

            log("Running deconvolution");

            double ObjectiveRI = 1.0;

            if ("oil".equals(exp.objectiveType)) {
                ObjectiveRI = 1.5115;
            }

            if ("water".equals(exp.objectiveType)) {
                ObjectiveRI = 1.33;
            }

            log("Waiting for deconvolution interlock");
            DeconvolutionInterlockDispatcher.gainLock();
            log("Interlock acquired");
            dec = new Deconvolve_mult(!exp.deconvolution.equals("Microvolution"), numDeconvolutionDevices, po.isUseBlindDeconvolution());

            dec.runDeconvolution(hyp, XYres, Zpitch, wavelenghths, deconvIterations, exp.deconvolutionModel, exp.drift_comp_channel - 1, exp.numerical_aperture, ObjectiveRI);

            DeconvolutionInterlockDispatcher.releaseLock();

            //Do background subtraction if needed
            ImagePlus reorderedHyp = hyp;
            if(exp.bgSub) {
                reorderedHyp = backgroundSubtraction(hyp, exp, baseDir, channelNames);
            }

            if (reorderedHyp.getNChannels() == 4) {
                ((CompositeImage) reorderedHyp).setLuts(new LUT[]{LUT.createLutFromColor(Color.WHITE), LUT.createLutFromColor(Color.RED), LUT.createLutFromColor(Color.GREEN), LUT.createLutFromColor(new Color(0, 70, 255))});
            } else if (reorderedHyp.getNChannels() == 3) {
                ((CompositeImage) reorderedHyp).setLuts(new LUT[]{LUT.createLutFromColor(Color.RED), LUT.createLutFromColor(Color.GREEN), LUT.createLutFromColor(new Color(0, 70, 255))});
            }

            //Save prcessed files as normal tiffs or image sequence
            if(!po.isExportImgSeq()) {
                String outStr = outDir + File.separator + Experiment.getDestStackFileName(exp.tiling_mode, tile, region, exp.region_width);
                log("Saving result tiff file: " + outStr);
                FileSaver fs = new FileSaver(reorderedHyp);
                fs.saveAsTiff(outStr);
            }
            else {
                String outStr = outDir + File.separator + FilenameUtils.removeExtension(exp.getDestStackFileName(exp.tiling_mode, tile, region, exp.region_width));
                File out = new File(outStr);
                if (!out.exists()) {
                    out.mkdirs();
                }
                log("Saving result file as image sequence: " + outStr);
                reorderedHyp.setTitle(FilenameUtils.removeExtension(exp.getDestStackFileName(exp.tiling_mode, tile, region, exp.region_width)));
                IJ.run(reorderedHyp, "Image Sequence... ", "format=TIFF save=" + outStr);

            }

            if (copy) {
                delete(new File(tmpDestDir));
            }

            String bestFocus = outDir + File.separator + "bestFocus" + File.separator;

            File bfFile = new File(bestFocus);
            if (!bfFile.exists()) {
                bfFile.mkdirs();
            }
            
            log("Running best focus");
            ImagePlus focused = BestFocus.createBestFocusStackFromHyperstack(reorderedHyp, bestFocusPlanes);
            log("Saving the focused tiff");
            FileSaver fs = new FileSaver(focused);
            fs.saveAsTiff(bestFocus + File.separator + Experiment.getDestStackFileNameWithZIndex(exp.tiling_mode, tile, region, exp.region_width, bestFocusPlanes[0]));
            
            WindowManager.closeAllWindows();

            exp.tile_width = reorderedHyp.getWidth();
            exp.tile_height = reorderedHyp.getHeight();

            exp.saveToFile(expFile);
            
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
            if (logStream != null) {
                e.printStackTrace(logStream);
                logStream.flush();
                logStream.close();
            }
            System.exit(2);
        }
    }

    private static void delete(File f) throws IOException {
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                delete(c);
            }
        }
        if (!f.delete()) {
            throw new FileNotFoundException("Failed to delete file: " + f);
        }
    }

    private static File logFile = null;
    private static PrintStream logStream;

    public static void log(String s) {
        logger.print(s);
        /*if (false && logFile == null) {
            Date date = new Date();
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yyyy_h-mm-ss");
            String formattedDate = sdf.format(date);

            logFile = new File("Driffta_log_" + formattedDate);
            try {
                logStream = new PrintStream(logFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
            logStream.println(s);
        }
        System.out.println(s);*/
    }

    @Override
    protected void finalize() throws Throwable {
        if (logStream != null) {
            logStream.println("writtenFromFinalize");
            logStream.flush();
            logStream.close();
        }
        DriftcompInterlockDispatcher.releaseLock();
        DeconvolutionInterlockDispatcher.releaseLock();
    }

    /**
     * Method to parse the content of exposureTimes.txt and store in a 2D String matrix
     * @param exposureTimes
     * @return
     * @throws IOException
     */
    private static String[][] parseExposureTimesTxtFile(File exposureTimes) throws IOException {
        Scanner in = new Scanner(exposureTimes);
        List<String[]> lines = new ArrayList<>();
        while(in.hasNextLine()) {
            String line = in.nextLine().trim();
            String[] splitStr = line.split("\\t");
            if(splitStr.length == 1) {
                splitStr = line.split(",");
            }
            lines.add(splitStr);
        }

        String[][] result = new String[lines.size()][];
        for(int i = 0; i<result.length; i++) {
            result[i] = lines.get(i);
        }

        return result;
    }

    /**
     * Preserving order by iterating through channel entries from channelNames field in experiment.json
     * @param expTimes
     * @param chNames
     * @return
     */
    private static HashMap<Integer, List<String>> createOrderlyMapForExpTimes(String[][] expTimes, String[] chNames) {
        LinkedHashMap<Integer, List<String>> chVsExp = new LinkedHashMap<>();
        for(String ch: chNames) {
            List firstRow = Arrays.asList(expTimes[0]);
            int index = firstRow.indexOf(ch);
            if(index != -1) {
                for(int i=0; i<expTimes.length; i++){
                    if (!chVsExp.containsKey(Arrays.asList(chNames).indexOf(ch) + 1)) {
                        List<String> expTimesForAChannel = new ArrayList<>();
                        chVsExp.put(Arrays.asList(chNames).indexOf(ch) + 1, expTimesForAChannel);
                    }
                    if(i != 0) {
                        chVsExp.get(Arrays.asList(chNames).indexOf(ch) + 1).add(expTimes[i][index]);
                    }
                }
            }
        }
        return chVsExp;
    }

    /**
     * Method to identify the channel-cycle that contains the blank cycles
     * Returns a list of blank cycles for every channel if present
     * @param channelNames
     * @param exp
     * @return
     */
    private static HashMap<Integer, List<Integer>> findBlankCyclesAndChannels(String[] channelNames, Experiment exp) {
        LinkedHashMap<Integer, List<Integer>> chVsCyc = new LinkedHashMap<>();
        for(int i=0; i<channelNames.length; i++) {
            int channels_count = exp.channel_names.length;
            if(channelNames[i] != null & channelNames[i].toLowerCase().contains("blank")) {
                logger.print("identified blank channel #"+i+", "+channelNames[i]);
                int ch = (i%channels_count) + 1;
                //String ch = exp.channel_names[(i%channels_count)];
                int cycle = (i/channels_count) + 1;
                if (!chVsCyc.containsKey(ch)) {
                    List<Integer> cycList = new ArrayList<>();
                    chVsCyc.put(ch, cycList);
                }
                chVsCyc.get(ch).add(cycle);
            }
        }
        logger.print("chVsCyc size "+ chVsCyc.size());
        chVsCyc.entrySet().forEach(e->logger.print(e.getKey(), e.getValue()));
        return chVsCyc;
    }

    /**
     * Method to find the channel-cycle with the highest exposure time for blank channels/cycles
     * Always returs one cycle with max exposure for one channel that has a blank cycle
     * @param expTimesForEveryCh
     * @param blankCyclesForEveryCh
     * @return
     */
    private static HashMap<Integer, Integer> getHighestExpCycForEveryChannel(HashMap<Integer, List<String>> expTimesForEveryCh, HashMap<Integer, List<Integer>> blankCyclesForEveryCh) {
        LinkedHashMap<Integer, Integer> maxExpTimeChVsCyc = new LinkedHashMap<>();
        for(Map.Entry<Integer, List<Integer>> blankCycEntry : blankCyclesForEveryCh.entrySet()) {
            List<Integer> blankCycForACh = blankCycEntry.getValue();
            int firstBlankCycIndex = blankCycForACh.get(0) - 1;
            List<String> expTimesForACh = expTimesForEveryCh.get(blankCycEntry.getKey());

            int cycForMaxExp = blankCycForACh.get(0);
            double maxExp = Double.parseDouble(expTimesForACh.get(firstBlankCycIndex));

            for(int i = 1; i<blankCycForACh.size(); i++) {
                double newMaxExp = Double.parseDouble(expTimesForACh.get(blankCycForACh.get(i) - 1));
                if(newMaxExp > maxExp) {
                    maxExp = newMaxExp;
                    cycForMaxExp = blankCycForACh.get(i);
                }
            }
            maxExpTimeChVsCyc.put(blankCycEntry.getKey(), cycForMaxExp);
        }
        return maxExpTimeChVsCyc;
    }

    /**
     * Method to perform background subtraction to eliminate noise from the processed image after drift compensation and deconvolution
     * @param hyp
     * @param exp
     * @param baseDir
     * @param channelNames
     * @throws IOException
     */
    private static ImagePlus backgroundSubtraction(ImagePlus hyp, Experiment exp, String baseDir, String[] channelNames) throws IOException {
        logger.print("doing BG subtraction");
        Duplicator dup = new Duplicator();

        File exposureTimesFile = new File(baseDir + File.separator + "exposure_times.txt");
        if(!exposureTimesFile.exists()) {
            throw new IllegalStateException("exposure_times.txt file not present. This is required for background subtraction to eliminate noise. Try again!");
        }
        String[][] exposureTimes = parseExposureTimesTxtFile(exposureTimesFile);
        populateExposureTimesMap();

        for(int i=0; i<exposureTimes.length; i++) {
            for(int j=0; j<exposureTimes[0].length; j++) {
                if(i == 0 || j == 0) {
                    continue;
                }
                if(expVsMs.containsKey(exposureTimes[i][j])) {
                    exposureTimes[i][j] = String.valueOf(expVsMs.get(exposureTimes[i][j]));
                }
            }
        }

        HashMap<Integer, List<String>> expTimesMapForChannels = createOrderlyMapForExpTimes(exposureTimes, exp.channel_names);
        HashMap<Integer, List<Integer>> blankCycMapForChannels = findBlankCyclesAndChannels(channelNames, exp);
        HashMap<Integer, Integer> maxExpChVsCyc = getHighestExpCycForEveryChannel(expTimesMapForChannels, blankCycMapForChannels);

        ArrayList<ImagePlus> stacks = new ArrayList<>();

        for(int ch = 1; ch <= hyp.getNChannels(); ch++) {

            logger.print("doing BG subtraction for channel #"+ch);

            boolean sub = maxExpChVsCyc.containsKey(ch);

            //Get zSlices stack for the channel-cycle that has maximum exposure time.
            ImagePlus maxBlankStack =  sub ? dup.run(hyp, ch, ch, 1, hyp.getNSlices(), maxExpChVsCyc.get(ch), maxExpChVsCyc.get(ch)):null;

            //Iterate over the stacks
            for(int fr=0; fr<hyp.getNFrames(); fr++) {
                if(!sub) {
                    ImagePlus st = dup.run(hyp, ch, ch, 1, hyp.getNSlices(), fr+1, fr+1);
                    stacks.add(st);
                    continue;
                }
                //Get a list of all exposure times for this channel(for all cycles)
                List<String> expTimes = expTimesMapForChannels.get(ch);

                //find the exposure time that corresponds to the blank cycle
                double expBlankStack = Double.parseDouble(expTimes.get(maxExpChVsCyc.get(ch)-1));

                //find the exposure time that corresponds to the current stack
                double expStack = Double.parseDouble(expTimes.get(fr));

                double r = expStack/expBlankStack;

                //multiply the blank stack image processor with the factor r
                maxBlankStack.getProcessor().multiply(r);

                ImageCalculator ic = new ImageCalculator();

                //Subtract the blank stack from the current stack and store it in the current
                ImagePlus st = ic.run("Subtract create stack",  dup.run(hyp, ch, ch, 1, hyp.getNSlices(), fr+1, fr+1), maxBlankStack);
                stacks.add(st);
            }
        }

        ImagePlus concatenatedStacks = new Concatenator().concatenate(stacks.toArray(new ImagePlus[stacks.size()]), false);
        ImagePlus newHyp = HyperStackConverter.toHyperStack(concatenatedStacks, hyp.getNChannels(), hyp.getNSlices(), hyp.getNFrames(), "xyztc", "composite");
        ImagePlus reorderedHyp = Hyperstack_rearranger.reorderHyperstack(newHyp, "CZT", false, false);

        return reorderedHyp;
    }

    /**
     * Method to populate the exposure times map which is used to store exposure time in ms
     */
    private static void populateExposureTimesMap() {
        expVsMs.put("skip", (double)0);
        expVsMs.put("1/7500s", (double)1000 * 1/7500);
        expVsMs.put("1/5500s", (double)1000 * 1/5500);
        expVsMs.put("1/4500s", (double)1000 * 1/4500);
        expVsMs.put("1/4000s", (double)1000 * 1/4000);
        expVsMs.put("1/3200s", (double)1000 * 1/3200);
        expVsMs.put("1/2800s", (double)1000 * 1/2800);
        expVsMs.put("1/2500s", (double)1000 * 1/2500);
        expVsMs.put("1/2250s", (double)1000 * 1/2250);
        expVsMs.put("1/2000s", (double)1000 * 1/2000);
        expVsMs.put("1/1500s", (double)1000 * 1/1500);
        expVsMs.put("1/1300s", (double)1000 * 1/1300);
        expVsMs.put("1/1100s", (double)1000 * 1/1100);
        expVsMs.put("1/1000s", (double)1000 * 1/1000);
        expVsMs.put("1/800s", (double)1000 * 1/800);
        expVsMs.put("1/700s", (double)1000 * 1/700);
        expVsMs.put("1/600s", (double)1000 * 1/600);
        expVsMs.put("1/500s", (double)1000 * 1/500);
        expVsMs.put("1/400s", (double)1000 * 1/400);
        expVsMs.put("1/350s", (double)1000 * 1/350);
        expVsMs.put("1/300s", (double)1000 * 1/300);
        expVsMs.put("1/250s", (double)1000 * 1/250);
        expVsMs.put("1/200s", (double)1000 * 1/200);
        expVsMs.put("1/175s", (double)1000 * 1/175);
        expVsMs.put("1/150s", (double)1000 * 1/150);
        expVsMs.put("1/120s", (double)1000 * 1/120);
        expVsMs.put("1/100s", (double)1000 * 1/100);
        expVsMs.put("1/80s", (double)1000 * 1/80);
        expVsMs.put("1/70s", (double)1000 * 1/70);
        expVsMs.put("1/60s", (double)1000 * 1/60);
        expVsMs.put("1/50s", (double)1000 * 1/50);
        expVsMs.put("1/40s", (double)1000 * 1/40);
        expVsMs.put("1/35s", (double)1000 * 1/35);
        expVsMs.put("1/30s", (double)1000 * 1/30);
        expVsMs.put("1/25s", (double)1000 * 1/25);
        expVsMs.put("1/20s", (double)1000 * 1/20);
        expVsMs.put("1/15s", (double)1000 * 1/15);
        expVsMs.put("1/12s", (double)1000 * 1/12);
        expVsMs.put("1/10s", (double)1000 * 1/10);
        expVsMs.put("1/8.5s", 1000 * 1/8.5);
        expVsMs.put("1/7.5s", 1000 * 1/7.5);
        expVsMs.put("1/6s", (double)1000 * 1/6);
        expVsMs.put("1/5s", (double)1000 * 1/5);
        expVsMs.put("1/4s", (double)1000 * 1/4);
        expVsMs.put("1/3.5s", 1000 * 1/3.5);
        expVsMs.put("1/3s", (double)1000 * 1/3);
        expVsMs.put("1/2.5s", 1000 * 1/2.50);
        expVsMs.put("1/2.3s", 1000 * 1/2.3);
        expVsMs.put("1/2s", (double)1000 * 1/2);
        expVsMs.put("1/1.7s", 1000 * 1/1.7);
        expVsMs.put("1/1.5s", 1000 * 1/1.5);
        expVsMs.put("1/1.2s", 1000 * 1/1.2);
        expVsMs.put("1s", (double)1000 * 1);
        expVsMs.put("1.2s", 1000 * 1.2);
        expVsMs.put("1.5s", 1000 * 1.5);
        expVsMs.put("2s", (double)1000 * 2);
        expVsMs.put("2.5s", (double)1000 * 2.5);
        expVsMs.put("3s", (double)1000 * 3);
        expVsMs.put("3.5s", 1000 * 3.5);
        expVsMs.put("4s", (double)1000 * 4);
        expVsMs.put("4.5s", (double)1000 * 4.5);
        expVsMs.put("5s", (double)1000 * 5);
    }
}
