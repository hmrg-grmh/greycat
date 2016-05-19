package org.mwg.ws;

import org.mwg.*;

import java.util.Arrays;


/**
 * Draft of test
 */
public class Test {
    public static void main(String[] args) {
        Graph serverGraph = GraphBuilder.builder().withStorage(new WSStorageWrapper(new LevelDBStorage("data"),8080))
                .build();

//        Graph serverGraph = GraphBuilder.builder().withStorage(new LevelDBStorage("datas"))
//                .build();


        WSStorageClient wsClient = null;
        try {
            wsClient = WSStorageClient.init("0.0.0.0",8080);
        } catch (Exception e) {
            e.printStackTrace();
//            Assert.fail();
        }
//        Assert.assertNotNull(wsClient);
        Graph clientGraph = GraphBuilder.builder().withStorage(wsClient).build();

        serverGraph.connect(new Callback<Boolean>() {
            @Override
            public void on(Boolean result) {
                clientGraph.connect(new Callback<Boolean>() {
                    @Override
                    public void on(Boolean result) {
                        Node node1 = serverGraph.newNode(0,0);
                        node1.set("name","node1");
                        final long id = node1.id();
//
//                        Node node2 = serverGraph.newNode(0,0);
//                        node2.set("name","node2");
//
//                        Node node3 = serverGraph.newNode(0,0);
//                        node3.set("name","node3");


                        serverGraph.index("indexName",node1,"name",null);
//                        serverGraph.index("indexName",node2,attIndex,null);
//                        serverGraph.index("indexName",node3,attIndex,null);


                        serverGraph.save(new Callback<Boolean>() {
                            @Override
                            public void on(Boolean result) {
                                clientGraph.all(0, 0, "indexName", new Callback<Node[]>() {
                                    @Override
                                    public void on(Node[] result) {
                                        System.out.println(Arrays.toString(result));
                                    }
                                });
//                                clientGraph.lookup(0, 0, id, new Callback<Node>() {
//                                    @Override
//                                    public void on(Node result) {
//                                        System.out.println(result);
//                                    }
//                                });
                            }
                        });


                    }
                });
            }
        });
    }
}
