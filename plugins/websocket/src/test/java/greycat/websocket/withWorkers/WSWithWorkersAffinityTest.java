/**
 * Copyright 2017-2019 The GreyCat Authors.  All rights reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package greycat.websocket.withWorkers;

import greycat.*;
import greycat.websocket.WSClientForWorkers;
import greycat.websocket.WSServerWithWorkers;
import greycat.workers.*;
import org.junit.*;
import org.junit.runners.MethodSorters;

import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.*;

/**
 * Created by Gregory NAIN on 2019-03-15.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class WSWithWorkersAffinityTest {

    private static WSServerWithWorkers wsServer;

    @BeforeClass
    public static void setUp() {
        CountDownLatch latch = new CountDownLatch(1);

        GraphBuilder graphBuilder = GraphBuilder.newBuilder().withPlugin(new PluginForWorkersTest());
        WorkerBuilderFactory defaultFactory = () -> DefaultWorkerBuilder.newBuilder().withGraphBuilder(graphBuilder);
        WorkerBuilderFactory defaultRootFactory = () -> DefaultRootWorkerBuilder.newBuilder().withGraphBuilder(graphBuilder);

        GraphWorkerPool workersPool = GraphWorkerPool.getInstance()
                .withRootWorkerBuilderFactory(defaultRootFactory)
                .withDefaultWorkerBuilderFactory(defaultFactory);

        workersPool.setOnPoolReady((worker, allSet) -> {
           allSet.run();
           latch.countDown();
        });
        workersPool.initialize();

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        workersPool.createWorker(WorkerAffinity.GENERAL_PURPOSE_WORKER, "GeneralPurposeWorker_0", null);

        wsServer = new WSServerWithWorkers(1234);
        wsServer.start();
    }

    @Test
    public void _00_defaultTask() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        GraphBuilder builder = GraphBuilder.newBuilder().withPlugin(new PluginForWorkersTest()).withStorage(new WSClientForWorkers("ws://localhost:1234/ws"));
        Graph graph = builder.build();
        graph.connect(graphConnected -> {

            Task createNode = Tasks.newTask()
                    .declareIndex("nodes", "name")
                    .createNode()
                    .setAttribute("name", Type.STRING, "Node 0")
                    .updateIndex("nodes")
                    .save();
            createNode.executeRemotely(graph, creationResult -> {
                if (creationResult.exception() != null) {
                    creationResult.exception().printStackTrace();
                }
                long ts1 = System.currentTimeMillis();
                assertNull(creationResult.exception());
                assertNull(creationResult.output());
                assertEquals(1, creationResult.size());
                assertTrue(creationResult.get(0) instanceof Node);
                assertEquals("Node 0", ((Node) creationResult.get(0)).get("name"));

                graph.disconnect(disconnected -> {
                    latch.countDown();
                });
            });
        });
        latch.await();
    }


    @Test
    public void _01_explicitGPTest() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        GraphBuilder builder = GraphBuilder.newBuilder().withPlugin(new PluginForWorkersTest()).withStorage(new WSClientForWorkers("ws://localhost:1234/ws"));
        Graph graph = builder.build();
        graph.connect(graphConnected -> {

            Task createNode = Tasks.newTask()
                    .declareIndex("nodes", "name")
                    .createNode()
                    .setAttribute("name", Type.STRING, "Node 1")
                    .updateIndex("nodes")
                    .save();

            TaskContext taskContext = createNode.prepare(graph, null, creationResult -> {
                if (creationResult.exception() != null) {
                    creationResult.exception().printStackTrace();
                }
                long ts1 = System.currentTimeMillis();
                assertNull(creationResult.exception());
                assertNull(creationResult.output());
                assertEquals(1, creationResult.size());
                assertTrue(creationResult.get(0) instanceof Node);
                assertEquals("Node 1", ((Node) creationResult.get(0)).get("name"));

                graph.disconnect(disconnected -> {
                    latch.countDown();
                });
            });
            taskContext.setWorkerAffinity(WorkerAffinity.GENERAL_PURPOSE_WORKER);
            createNode.executeRemotelyUsing(taskContext);
        });
        latch.await();
    }


    @Test
    public void _02_sessionWorkerTest() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        GraphBuilder builder = GraphBuilder.newBuilder().withPlugin(new PluginForWorkersTest()).withStorage(new WSClientForWorkers("ws://localhost:1234/ws"));
        Graph graph = builder.build();
        graph.connect(graphConnected -> {

            //Create a node in a session worker without saving
            Task createNode = Tasks.newTask()
                    .declareIndex("nodes", "name")
                    .createNode()
                    .setAttribute("name", Type.STRING, "Node 2")
                    .updateIndex("nodes")
                    .save();

            TaskContext taskContext = createNode.prepare(graph, null, creationResult -> {
                if (creationResult.exception() != null) {
                    creationResult.exception().printStackTrace();
                }
                long ts1 = System.currentTimeMillis();
                assertNull(creationResult.exception());
                assertNull(creationResult.output());
                assertEquals(1, creationResult.size());
                assertTrue(creationResult.get(0) instanceof Node);
                assertEquals("Node 2", ((Node) creationResult.get(0)).get("name"));


                //Try to read the node in the session worker
                Task collectNode = Tasks.newTask()
                        .readIndex("nodes", "Node 0");

                TaskContext collectTaskContext = collectNode.prepare(graph, null, collectionResult -> {
                    if (collectionResult.exception() != null) {
                        collectionResult.exception().printStackTrace();
                    }
                    assertNull(collectionResult.exception());
                    assertNull(collectionResult.output());
                    assertEquals(1, collectionResult.size());
                    assertTrue(collectionResult.get(0) instanceof Node);
                    assertEquals("Node 0", ((Node) collectionResult.get(0)).get("name"));

                    graph.disconnect(disconnected -> {
                        latch.countDown();
                    });
                });
                collectTaskContext.setWorkerAffinity(WorkerAffinity.SESSION_WORKER);
                collectNode.executeRemotelyUsing(collectTaskContext);

            });
            taskContext.setWorkerAffinity(WorkerAffinity.SESSION_WORKER);
            createNode.executeRemotelyUsing(taskContext);
        });
        latch.await();
    }


    @Test
    public void _03_taskWorkerTest() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        GraphBuilder builder = GraphBuilder.newBuilder().withPlugin(new PluginForWorkersTest()).withStorage(new WSClientForWorkers("ws://localhost:1234/ws"));
        Graph graph = builder.build();
        graph.connect(graphConnected -> {

            //Create a node in a session worker without saving
            Task createNode = Tasks.newTask()
                    .declareIndex("nodes", "name")
                    .createNode()
                    .setAttribute("name", Type.STRING, "Node 3")
                    .updateIndex("nodes")
                    .save();

            TaskContext taskContext = createNode.prepare(graph, null, creationResult -> {
                if (creationResult.exception() != null) {
                    creationResult.exception().printStackTrace();
                }
                long ts1 = System.currentTimeMillis();
                assertNull(creationResult.exception());
                assertNull(creationResult.output());
                assertEquals(1, creationResult.size());
                assertTrue(creationResult.get(0) instanceof Node);
                assertEquals("Node 3", ((Node) creationResult.get(0)).get("name"));


                //Try to read the node in the session worker
                Task collectNode = Tasks.newTask()
                        .readIndex("nodes", "Node 0");

                TaskContext collectTaskContext = collectNode.prepare(graph, null, collectionResult -> {
                    if (collectionResult.exception() != null) {
                        collectionResult.exception().printStackTrace();
                    }
                    assertNull(collectionResult.exception());
                    assertNull(collectionResult.output());
                    assertEquals(1, collectionResult.size());
                    assertTrue(collectionResult.get(0) instanceof Node);
                    assertEquals("Node 0", ((Node) collectionResult.get(0)).get("name"));

                    graph.disconnect(disconnected -> {
                        latch.countDown();
                    });
                });
                collectTaskContext.setWorkerAffinity(WorkerAffinity.TASK_WORKER);
                collectNode.executeRemotelyUsing(collectTaskContext);

            });
            taskContext.setWorkerAffinity(WorkerAffinity.TASK_WORKER);
            createNode.executeRemotelyUsing(taskContext);
        });
        latch.await();
    }



    @AfterClass
    public static void tearDown() {
        wsServer.stop();
        GraphWorkerPool.getInstance().halt();
    }


}
