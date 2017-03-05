///////////////////////////////////////////////////////////////////////////////
// For information as to what this class does, see the Javadoc, below.       //
// Copyright (C) 1998, 1999, 2000, 2001, 2002, 2003, 2004, 2005, 2006,       //
// 2007, 2008, 2009, 2010, 2014, 2015 by Peter Spirtes, Richard Scheines, Joseph   //
// Ramsey, and Clark Glymour.                                                //
//                                                                           //
// This program is free software; you can redistribute it and/or modify      //
// it under the terms of the GNU General Public License as published by      //
// the Free Software Foundation; either version 2 of the License, or         //
// (at your option) any later version.                                       //
//                                                                           //
// This program is distributed in the hope that it will be useful,           //
// but WITHOUT ANY WARRANTY; without even the implied warranty of            //
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the             //
// GNU General Public License for more details.                              //
//                                                                           //
// You should have received a copy of the GNU General Public License         //
// along with this program; if not, write to the Free Software               //
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA //
///////////////////////////////////////////////////////////////////////////////

package edu.cmu.tetrad.search;

import edu.cmu.tetrad.data.*;
import edu.cmu.tetrad.graph.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static edu.cmu.tetrad.util.StatUtils.covariance;
import static java.lang.Math.abs;
import static java.lang.Math.signum;

/**
 * Fast adjacency search followed by robust skew orientation. Checks are done for adding
 * two-cycles. The two-cycle checks do not require non-Gaussianity. The robust skew
 * orientation of edges left or right does.
 *
 * @author Joseph Ramsey
 */
public final class Fang implements GraphSearch {

    // Elapsed time of the search, in milliseconds.
    private long elapsed = 0;

    // The data sets being analyzed. They must all have the same variables and the same
    // number of records.
    private List<DataSet> dataSets = null;

    // For the Fast Adjacency Search.
    private int depth = -1;

    // For the SEM BIC score, for the Fast Adjacency Search.
    private double penaltyDiscount = 1;

    // Knowledge the the search will obey, of forbidden and required edges.
    private IKnowledge knowledge = new Knowledge2();

    /**
     * @param dataSets These datasets must all have the same variables, in the same order.
     */
    public Fang(List<DataSet> dataSets) {
        this.dataSets = dataSets;
    }

    //======================================== PUBLIC METHODS ====================================//

    /**
     * Runs the search on the concatenated data, returning a graph, possibly cyclic, possibly with
     * two-cycles. Runs the fast adjacency search (FAS, Spirtes et al., 2000) follows by a modification
     * of the robust skew rule (Pairwise Likelihood Ratios for Estimation of Non-Gaussian Structural
     * Equation Models, Smith and Hyvarinen), together with some heuristics for orienting two-cycles.
     *
     * @return the graph. Some of the edges may be undirected (though it shouldn't be many in most cases)
     * and some of the adjacencies may be two-cycles.
     */
    public Graph search() {
        long start = System.currentTimeMillis();

        List<DataSet> _dataSets = new ArrayList<>();
        for (DataSet dataSet : dataSets) _dataSets.add(DataUtils.standardizeData(dataSet));

        DataSet dataSet = DataUtils.concatenate(_dataSets);

        DataSet dataSet2 = dataSet.copy();

        for (int i = 0; i < dataSet2.getNumRows(); i++) {
            for (int j = 0; j < dataSet2.getNumColumns(); j++) {
                if (dataSet2.getDouble(i, j) < 0) dataSet2.setDouble(i, j, 0);
            }
        }

        SemBicScore score = new SemBicScore(new CovarianceMatrix(dataSet2));
        score.setPenaltyDiscount(penaltyDiscount);
        IndependenceTest test = new IndTestScore(score, dataSet2);

        List<Node> variables = dataSet.getVariables();

        double[][] colData = dataSet.getDoubleData().transpose().toArray();

        FasStableConcurrent fas = new FasStableConcurrent(test);
        fas.setDepth(getDepth());
        fas.setVerbose(false);
        fas.setKnowledge(knowledge);
        Graph G0 = fas.search();
        SearchGraphUtils.pcOrientbk(knowledge, G0, G0.getNodes());

        Graph graph = new EdgeListGraph(variables);

        for (int i = 0; i < variables.size(); i++) {
            for (int j = i + 1; j < variables.size(); j++) {
                Node X = variables.get(i);
                Node Y = variables.get(j);

                int n = colData[i].length;

                final double[] x = new double[n];
                final double[] y = new double[n];

                for (int k = 0; k < n; k++) {
                    x[k] = colData[i][k];
                    y[k] = colData[j][k];
                }

                double sxyxp = 0.0;
                double sxyyp = 0.0;
                double sxxxp = 0.0;
                double syyyp = 0.0;

                int na = 0;
                int nb = 0;

                for (int k = 0; k < n; k++) {
                    if (x[k] > 0) {
                        sxyxp += x[k] * y[k];
                        sxxxp += x[k] * x[k];
                        na++;
                    }

                    if (y[k] > 0) {
                        sxyyp += x[k] * y[k];
                        syyyp += y[k] * y[k];
                        nb++;
                    }
                }

                sxyxp /= na;
                sxyyp /= nb;
                sxxxp /= na;
                syyyp /= nb;

                double q1 = sxyxp / sxxxp;
                double q2 = sxyyp / syyyp;


                if (G0.isAdjacentTo(X, Y) || ((q1 < 0.2 || q2 < 0.2) && abs(q1 - q2) > 0.2)) {
                    double c = covarianceOfPart(x, y, 0, 0);
                    double c1 = covarianceOfPart(x, y, 1, 0);
                    double c2 = covarianceOfPart(x, y, 0, 1);
                    double c3 = covarianceOfPart(x, y, -1, 0);
                    double c4 = covarianceOfPart(x, y, 0, -1);

                    double R = c * (sxyxp - sxyyp);

                    if (knowledgeOrients(X, Y)) {
                        graph.addDirectedEdge(X, Y);
                    } else if (knowledgeOrients(Y, X)) {
                        graph.addDirectedEdge(Y, X);
                    } else if (!(signum(c) == signum(c1) && signum(c) == signum(c3))
                            && !(signum(c) == signum(c2) && signum(c) == signum(c4))) {
                        Edge edge1 = Edges.directedEdge(X, Y);
                        Edge edge2 = Edges.directedEdge(Y, X);

                        edge1.setLineColor(Color.RED);
                        edge2.setLineColor(Color.RED);

                        graph.addEdge(edge1);
                        graph.addEdge(edge2);
                    } else if ((abs(q1) > 0.5 && abs(q2) > 0.5)) {
                        Edge edge1 = Edges.directedEdge(X, Y);
                        Edge edge2 = Edges.directedEdge(Y, X);

                        edge1.setLineColor(Color.GREEN);
                        edge2.setLineColor(Color.GREEN);

                        graph.addEdge(edge1);
                        graph.addEdge(edge2);
                    } else if (R > 0) {
                        graph.addDirectedEdge(X, Y);
                    } else if (R < 0) {
                        graph.addDirectedEdge(Y, X);
                    } else {
                        graph.addUndirectedEdge(X, Y);
                    }
                }
            }
        }

        long stop = System.currentTimeMillis();
        this.elapsed = stop - start;

        return graph;
    }

    private double covarianceOfPart(double[] xData, double[] yData, int q, int s) {
        final int n = xData.length;

        double[] x = new double[n];
        double[] y = new double[n];

        for (int i = 0; i < n; i++) {
            if (q == 0 && s == 0) {
                x[i] = xData[i];
                y[i] = yData[i];
            } else if (q == 1 && s == 0 && xData[i] > 0) {
                x[i] = xData[i];
                y[i] = yData[i];
            } else if (q == 0 && s == 1 && yData[i] > 0) {
                x[i] = xData[i];
                y[i] = yData[i];
            } else if (q == -1 && s == 0 && xData[i] < 0) {
                x[i] = xData[i];
                y[i] = yData[i];
            } else if (q == 0 && s == -1 && yData[i] < 0) {
                x[i] = xData[i];
                y[i] = yData[i];
            }
        }

        x = nonzero(x);
        y = nonzero(y);

        return covariance(x, y);
    }

    private double[] nonzero(double[] q3) {
        int N = 0;

        for (double aQ3 : q3) {
            if (aQ3 != 0) N++;
        }

        double[] ret = new double[N];

        int t = 0;

        for (double aQ3 : q3) {
            if (aQ3 != 0) {
                ret[t++] = aQ3;
            }
        }

        return ret;
    }

    /**
     * @return The depth of search for the Fast Adjacency Search (FAS).
     */
    public int getDepth() {
        return depth;
    }

    /**
     * @param depth The depth of search for the Fast Adjacency Search (S). The default is -1.
     *              unlimited. Making this too high may results in statistical errors.
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }

    /**
     * @return The elapsed time in milliseconds.
     */
    public long getElapsedTime() {
        return elapsed;
    }

    /**
     * @return Returns the penalty discount used for the adjacency search. The default is 1,
     * though a higher value is recommended, say, 2, 3, or 4.
     */
    public double getPenaltyDiscount() {
        return penaltyDiscount;
    }

    /**
     * @param penaltyDiscount Sets the penalty discount used for the adjacency search.
     *                        The default is 1, though a higher value is recommended, say,
     *                        2, 3, or 4.
     */
    public void setPenaltyDiscount(double penaltyDiscount) {
        this.penaltyDiscount = penaltyDiscount;
    }

    /**
     * @return the current knowledge.
     */
    public IKnowledge getKnowledge() {
        return knowledge;
    }

    /**
     * @param knowledge Knowledge of forbidden and required edges.
     */
    public void setKnowledge(IKnowledge knowledge) {
        this.knowledge = knowledge;
    }

    //======================================== PRIVATE METHODS ====================================//

    private boolean knowledgeOrients(Node left, Node right) {
        return knowledge.isForbidden(right.getName(), left.getName()) || knowledge.isRequired(left.getName(), right.getName());
    }
}






