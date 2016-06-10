package common.matrix;

import org.apache.commons.math3.distribution.MultivariateNormalDistribution;
import org.junit.Assert;
import org.junit.Test;
import org.mwg.Callback;
import org.mwg.Graph;
import org.mwg.GraphBuilder;
import org.mwg.Node;
import org.mwg.core.scheduler.NoopScheduler;
import org.mwg.ml.algorithm.profiling.GaussianGmmNode;
import org.mwg.ml.common.matrix.Matrix;
import org.mwg.ml.common.matrix.operation.Gaussian1D;

import java.util.Random;

/**
 * Created by assaad on 03/05/16.
 */
public class GaussianProbaTest {
    @Test
    public void Gaussian1D() {
        Graph graph = new GraphBuilder().addNodeType(new GaussianGmmNode.Factory()).withScheduler(new NoopScheduler()).build();
        graph.connect(new Callback<Boolean>() {
            @Override
            public void on(Boolean result) {
                GaussianGmmNode gaussianNode = (GaussianGmmNode) graph.newTypedNode(0, 0, "GaussianGmm");
                double eps = 1e-7;


                int total = 16;
                double[][] train = new double[total][1];
                Random rand = new Random();

                double sum = 0;
                double sumsquare = 0;
                for (int i = 0; i < 16; i++) {
                    train[i][0] = rand.nextDouble() * 100;
                    sum += train[i][0];
                    sumsquare += train[i][0] * train[i][0];

                    int finalI = i;
                    gaussianNode.jump(i, new Callback<Node>() {
                        @Override
                        public void on(Node result) {
                            gaussianNode.learnVector(train[finalI],new Callback<Boolean>() {
                                @Override
                                public void on(Boolean result) {

                                }
                            });
                            result.free();
                        }
                    });

                }

                double finalSum = sum;
                double finalSumsquare = sumsquare;
                gaussianNode.jump(16, new Callback<GaussianGmmNode>() {
                    @Override
                    public void on(GaussianGmmNode result) {
                        double[] avgBatch = result.getAvg();
                        Matrix covBatch = result.getCovariance(avgBatch,null);

                        //System.out.println("Avg: " + avgBatch[0] + " " + sum / total);
                        //System.out.println("Var: " + covBatch[0][0] + " " + Gaussian1D.getCovariance(sum, sumsquare, total));
                        Assert.assertTrue(Math.abs(avgBatch[0] - finalSum / total) < eps);
                        Assert.assertTrue(Math.abs(covBatch.get(0,0) - Gaussian1D.getCovariance(finalSum, finalSumsquare, total)) < eps);

                        double testvec = rand.nextDouble() * 100;
                        //System.out.println("Prob: " + Gaussian1D.getDensity(sum, sumsquare, total, testvec) + " " + gaussianNodeBatch.getProbability(new double[]{testvec}, null, false));
                        Assert.assertTrue(Math.abs(Gaussian1D.getDensity(finalSum, finalSumsquare, total, testvec) - result.getProbability(new double[]{testvec}, null, false)) < eps);

                    }
                });


            }
        });

    }

    @Test
    public void MultinomialTest() {
        Graph graph = new GraphBuilder().addNodeType(new GaussianGmmNode.Factory()).withScheduler(new NoopScheduler()).build();
        graph.connect(new Callback<Boolean>() {
            @Override
            public void on(Boolean result) {

                GaussianGmmNode gaussianNodeLive = (GaussianGmmNode) graph.newTypedNode(0, 0, "GaussianGmm");

                gaussianNodeLive.set(GaussianGmmNode.FROM, "f1;f2");

                int test = 100;
                int feat = 2;

                double[][] b = new double[test][feat];
                double[] v = new double[feat];
                Random random = new Random();

                for (int i = 0; i < test; i++) {
                    for (int j = 0; j < feat; j++) {
                        v[j] = random.nextDouble() * (1 + 100 * j);
                        b[i][j] = v[j];
                    }
                    gaussianNodeLive.set("f1", b[i][0]);
                    gaussianNodeLive.set("f2", b[i][1]);

                    gaussianNodeLive.learn(new Callback<Boolean>() {
                        @Override
                        public void on(Boolean result) {

                        }
                    });
                }

                double[] ravg = gaussianNodeLive.getAvg();
                Matrix rcovData = gaussianNodeLive.getCovariance(ravg,null);


                double[][] temp=new double[rcovData.rows()][];
                for(int i=0;i<rcovData.rows();i++){
                    temp[i]=new double[rcovData.columns()];
                    for(int j=0;j<rcovData.columns();j++){
                        temp[i][j]=rcovData.get(i,j);
                    }
                }

                //Test probability calculation.
                MultivariateNormalDistribution apache = new MultivariateNormalDistribution(ravg, temp);

                double eps = 1e-8;
                double d = apache.density(v);
                //System.out.println("apache: " + d);

                double y = gaussianNodeLive.getProbability(v, null, false);
                //System.out.println("live: " + y);


                Assert.assertTrue(Math.abs(d - y) < eps);

            }
        });
    }

    @Test
    public void Singularity() {
        Graph graph = new GraphBuilder().addNodeType(new GaussianGmmNode.Factory()).withScheduler(new NoopScheduler()).build();
        graph.connect(new Callback<Boolean>() {
            @Override
            public void on(Boolean result) {
                double[] data = new double[3];
                double[] datan = new double[4];

                Random rand = new Random();

                GaussianGmmNode node1 = (GaussianGmmNode) graph.newTypedNode(0, 0, "GaussianGmm");
                GaussianGmmNode node2 = (GaussianGmmNode) graph.newTypedNode(0, 0, "GaussianGmm");

                node1.set(GaussianGmmNode.FROM, "f1;f2;f3");
                node2.set(GaussianGmmNode.FROM, "f1;f2;f3;f4");

                for (int i = 0; i < 1000; i++) {
                    data[0] = 8 + rand.nextDouble() * 4; //avg =10, [8,12]
                    data[1] = 90 + rand.nextDouble() * 20; //avg=100 [90,110]
                    data[2] = -60 + rand.nextDouble() * 20; //avg=-50 [-60,-40]

                    datan[0] = data[0];
                    datan[1] = data[1];
                    datan[2] = data[2];
                    datan[3] = 0 * data[0] + 0 * data[1] + 0 * data[2];

                    node1.set("f1", data[0]);
                    node1.set("f2", data[1]);
                    node1.set("f3", data[2]);

                    node2.set("f1", datan[0]);
                    node2.set("f2", datan[1]);
                    node2.set("f3", datan[2]);
                    node2.set("f4", datan[3]);


                    node1.learn(new Callback<Boolean>() {
                        @Override
                        public void on(Boolean result) {

                        }
                    });

                    node2.learn(new Callback<Boolean>() {
                        @Override
                        public void on(Boolean result) {

                        }
                    });
                }

                double[] avg = node1.getAvg();
                double[] avg2 = node2.getAvg();

                //printd(avg);
                //printd(avg2);

                data[0] = 10;
                data[1] = 100;
                data[2] = -60;

                datan[0] = data[0];
                datan[1] = data[1];
                datan[2] = data[2];
                datan[3] = 0 * data[0] + 0 * data[1] + 0 * data[2];

                double p = node1.getProbability(avg, null, false);
                double p2 = node2.getProbability(avg2, null, false);
                Assert.assertTrue(Math.abs(p - p2) < 1e-5);
                //System.out.println("p1: " + p);
                // System.out.println("p2: " + p2);


            }

            private void printd(double[] avg) {
                for (double d : avg) {
                    System.out.print(d + " ");
                }
                System.out.println();

            }
        });
    }
}
