package edu.cmu.tetrad.algcomparison.simulation;

import edu.cmu.tetrad.algcomparison.Simulation;
import edu.cmu.tetrad.data.DataSet;
import edu.cmu.tetrad.data.Discretizer;
import edu.cmu.tetrad.graph.Graph;
import edu.cmu.tetrad.graph.GraphUtils;
import edu.cmu.tetrad.graph.Node;
import edu.cmu.tetrad.sem.SemIm;
import edu.cmu.tetrad.sem.SemPm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Created by jdramsey on 6/4/16.
 */
public class LinearGaussianSemSimulation implements Simulation {
    private Graph graph;
    private DataSet dataSet;

    public LinearGaussianSemSimulation() {
    }

    public void simulate(Map<String, Number> parameters) {
        this.graph = GraphUtils.randomGraphRandomForwardEdges(
                parameters.get("numMeasures").intValue(),
                parameters.get("numLatents").intValue(),
                parameters.get("numEdges").intValue(),
                parameters.get("maxDegree").intValue(),
                parameters.get("maxIndegree").intValue(),
                parameters.get("maxOutdegree").intValue(),
                parameters.get("connected").intValue() == 1);
        SemPm pm = new SemPm(graph);
        SemIm im = new SemIm(pm);
        this.dataSet = im.simulateData(parameters.get("sampleSize").intValue(), false);
    }

    public Graph getDag() {
        return graph;
    }

    public DataSet getData() {
        return dataSet;
    }

    public String toString() {
        return "Linear, Gaussian SEM simulation";
    }

    public boolean isMixed() {
        return false;
    }
}