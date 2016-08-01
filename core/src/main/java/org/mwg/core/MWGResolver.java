package org.mwg.core;

import org.mwg.Callback;
import org.mwg.Constants;
import org.mwg.Node;
import org.mwg.Type;
import org.mwg.core.chunk.StateChunk;
import org.mwg.core.chunk.TimeTreeChunk;
import org.mwg.core.chunk.TreeWalker;
import org.mwg.core.chunk.WorldOrderChunk;
import org.mwg.core.utility.BufferBuilder;
import org.mwg.core.utility.DataHasher;
import org.mwg.plugin.*;
import org.mwg.struct.Buffer;
import org.mwg.struct.BufferIterator;
import org.mwg.struct.LongLongMap;
import org.mwg.struct.StringLongMap;

final class MWGResolver implements Resolver {

    private final Storage _storage;

    private final ChunkSpace _space;

    private final NodeTracker _tracker;

    private org.mwg.Graph _graph;

    private StateChunk dictionary;

    private WorldOrderChunk globalWorldOrderChunk;

    private static int KEY_SIZE = 3;

    MWGResolver(Storage p_storage, ChunkSpace p_space, NodeTracker p_tracker) {
        this._storage = p_storage;
        this._space = p_space;
        this._tracker = p_tracker;
    }

    @Override
    public final void init(org.mwg.Graph graph) {
        _graph = graph;
        dictionary = (StateChunk) this._space.getAndMark(ChunkType.STATE_CHUNK, CoreConstants.GLOBAL_DICTIONARY_KEY[0], CoreConstants.GLOBAL_DICTIONARY_KEY[1], CoreConstants.GLOBAL_DICTIONARY_KEY[2]);
        globalWorldOrderChunk = (WorldOrderChunk) this._space.getAndMark(ChunkType.WORLD_ORDER_CHUNK, 0, 0, Constants.NULL_LONG);
    }

    @Override
    public final String typeName(Node node) {
        return hashToString(typeCode(node));
    }

    @Override
    public final long typeCode(Node node) {
        final AbstractNode casted = (AbstractNode) node;
        WorldOrderChunk worldOrderChunk = (WorldOrderChunk) this._space.getByIndex(casted._index_worldOrder);
        if (worldOrderChunk == null) {
            return Constants.NULL_LONG;
        }
        return worldOrderChunk.extra();
    }

    @Override
    public final void initNode(final org.mwg.Node node, final long codeType) {
        final AbstractNode casted = (AbstractNode) node;
        final StateChunk cacheEntry_0 = (StateChunk) this._space.create(ChunkType.STATE_CHUNK, node.world(), node.time(), node.id(), null, null);
        //put and mark
        final StateChunk cacheEntry = (StateChunk) this._space.putAndMark(cacheEntry_0);
        if (cacheEntry_0 != cacheEntry) {
            this._space.freeChunk(cacheEntry_0);
        }
        //declare dirty now because potentially no insert could be done
        this._space.declareDirty(cacheEntry);
        //initiate superTime management
        final TimeTreeChunk superTimeTree_0 = (TimeTreeChunk) this._space.create(ChunkType.TIME_TREE_CHUNK, node.world(), Constants.NULL_LONG, node.id(), null, null);
        final TimeTreeChunk superTimeTree = (TimeTreeChunk) this._space.putAndMark(superTimeTree_0);
        if (superTimeTree != superTimeTree_0) {
            this._space.freeChunk(superTimeTree_0);
        }
        superTimeTree.insert(node.time());
        //initiate time management
        final TimeTreeChunk timeTree_0 = (TimeTreeChunk) this._space.create(ChunkType.TIME_TREE_CHUNK, node.world(), node.time(), node.id(), null, null);
        TimeTreeChunk timeTree = (TimeTreeChunk) this._space.putAndMark(timeTree_0);
        if (timeTree_0 != timeTree) {
            this._space.freeChunk(timeTree_0);
        }
        timeTree.insert(node.time());
        //initiate universe management
        final WorldOrderChunk objectWorldOrder_0 = (WorldOrderChunk) this._space.create(ChunkType.WORLD_ORDER_CHUNK, 0, 0, node.id(), null, null);
        final WorldOrderChunk objectWorldOrder = (WorldOrderChunk) this._space.putAndMark(objectWorldOrder_0);
        if (objectWorldOrder_0 != objectWorldOrder) {
            this._space.freeChunk(objectWorldOrder_0);
        }
        objectWorldOrder.put(node.world(), node.time());
        objectWorldOrder.setExtra(codeType);
        casted._index_stateChunk = cacheEntry.index();
        casted._index_timeTree = timeTree.index();
        casted._index_superTimeTree = superTimeTree.index();
        casted._index_worldOrder = objectWorldOrder.index();

        casted._world_magic = -1;
        casted._super_time_magic = -1;
        casted._time_magic = -1;

        //monitor the node object
        this._tracker.monitor(node);
        //last step call the user code
        casted.init();
    }

    @Override
    public final void initWorld(long parentWorld, long childWorld) {
        globalWorldOrderChunk.put(childWorld, parentWorld);
    }

    @Override
    public final void freeNode(org.mwg.Node node) {
        final AbstractNode casted = (AbstractNode) node;
        casted.cacheLock();
        if (!casted._dead) {
            this._space.unmarkByIndex(casted._index_stateChunk);
            this._space.unmarkByIndex(casted._index_timeTree);
            this._space.unmarkByIndex(casted._index_superTimeTree);
            this._space.unmarkByIndex(casted._index_worldOrder);
            casted._dead = true;
        }
        casted.cacheUnlock();
    }

    @Override
    public final <A extends org.mwg.Node> void lookup(long world, long time, long id, Callback<A> callback) {
        final MWGResolver selfPointer = this;
        try {
            selfPointer.getOrLoadAndMark(ChunkType.WORLD_ORDER_CHUNK, 0, 0, id, new Callback<Chunk>() {
                @Override
                public void on(final Chunk theNodeWorldOrder) {
                    if (theNodeWorldOrder == null) {
                        callback.on(null);
                    } else {
                        final long closestWorld = selfPointer.resolve_world(globalWorldOrderChunk, (WorldOrderChunk) theNodeWorldOrder, time, world);
                        selfPointer.getOrLoadAndMark(ChunkType.TIME_TREE_CHUNK, closestWorld, Constants.NULL_LONG, id, new Callback<Chunk>() {
                            @Override
                            public void on(final Chunk theNodeSuperTimeTree) {
                                if (theNodeSuperTimeTree == null) {
                                    selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                    callback.on(null);
                                } else {
                                    final long closestSuperTime = ((TimeTreeChunk) theNodeSuperTimeTree).previousOrEqual(time);
                                    if (closestSuperTime == Constants.NULL_LONG) {
                                        selfPointer._space.unmarkChunk(theNodeSuperTimeTree);
                                        selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                        callback.on(null);
                                        return;
                                    }
                                    selfPointer.getOrLoadAndMark(ChunkType.TIME_TREE_CHUNK, closestWorld, closestSuperTime, id, new Callback<Chunk>() {
                                        @Override
                                        public void on(final Chunk theNodeTimeTree) {
                                            if (theNodeTimeTree == null) {
                                                selfPointer._space.unmarkChunk(theNodeSuperTimeTree);
                                                selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                                callback.on(null);
                                            } else {
                                                final long closestTime = ((TimeTreeChunk) theNodeTimeTree).previousOrEqual(time);
                                                if (closestTime == Constants.NULL_LONG) {
                                                    selfPointer._space.unmarkChunk(theNodeTimeTree);
                                                    selfPointer._space.unmarkChunk(theNodeSuperTimeTree);
                                                    selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                                    callback.on(null);
                                                    return;
                                                }
                                                selfPointer.getOrLoadAndMark(ChunkType.STATE_CHUNK, closestWorld, closestTime, id, new Callback<Chunk>() {
                                                    @Override
                                                    public void on(Chunk theObjectChunk) {
                                                        if (theObjectChunk == null) {
                                                            selfPointer._space.unmarkChunk(theNodeTimeTree);
                                                            selfPointer._space.unmarkChunk(theNodeSuperTimeTree);
                                                            selfPointer._space.unmarkChunk(theNodeWorldOrder);
                                                            callback.on(null);
                                                        } else {
                                                            WorldOrderChunk castedNodeWorldOrder = (WorldOrderChunk) theNodeWorldOrder;
                                                            long extraCode = castedNodeWorldOrder.extra();
                                                            NodeFactory resolvedFactory = null;
                                                            if (extraCode != Constants.NULL_LONG) {
                                                                resolvedFactory = ((CoreGraph) selfPointer._graph).factoryByCode(extraCode);
                                                            }
                                                            AbstractNode resolvedNode;
                                                            if (resolvedFactory == null) {
                                                                resolvedNode = new CoreNode(world, time, id, selfPointer._graph);
                                                            } else {
                                                                resolvedNode = (AbstractNode) resolvedFactory.create(world, time, id, selfPointer._graph);
                                                            }
                                                            resolvedNode._dead = false;
                                                            resolvedNode._index_stateChunk = theObjectChunk.index();
                                                            resolvedNode._index_superTimeTree = theNodeSuperTimeTree.index();
                                                            resolvedNode._index_timeTree = theNodeTimeTree.index();
                                                            resolvedNode._index_worldOrder = theNodeWorldOrder.index();

                                                            if (closestWorld == world && closestTime == time) {
                                                                resolvedNode._world_magic = -1;
                                                                resolvedNode._super_time_magic = -1;
                                                                resolvedNode._time_magic = -1;
                                                            } else {
                                                                resolvedNode._world_magic = ((WorldOrderChunk) theNodeWorldOrder).magic();
                                                                resolvedNode._super_time_magic = ((TimeTreeChunk) theNodeSuperTimeTree).magic();
                                                                resolvedNode._time_magic = ((TimeTreeChunk) theNodeTimeTree).magic();
                                                            }

                                                            selfPointer._tracker.monitor(resolvedNode);
                                                            if (callback != null) {
                                                                final Node casted = resolvedNode;
                                                                callback.on((A) casted);
                                                            }
                                                        }
                                                    }
                                                });
                                            }
                                        }
                                    });
                                }
                            }
                        });
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private long resolve_world(final LongLongMap globalWorldOrder, final LongLongMap nodeWorldOrder, final long timeToResolve, long originWorld) {
        if (globalWorldOrder == null || nodeWorldOrder == null) {
            return originWorld;
        }
        long currentUniverse = originWorld;
        long previousUniverse = Constants.NULL_LONG;
        long divergenceTime = nodeWorldOrder.get(currentUniverse);
        while (currentUniverse != previousUniverse) {
            //check range
            if (divergenceTime != Constants.NULL_LONG && divergenceTime <= timeToResolve) {
                return currentUniverse;
            }
            //next round
            previousUniverse = currentUniverse;
            currentUniverse = globalWorldOrder.get(currentUniverse);
            divergenceTime = nodeWorldOrder.get(currentUniverse);
        }
        return originWorld;
    }

    private void getOrLoadAndMark(final byte type, final long world, final long time, final long id, final Callback<Chunk> callback) {
        if (world == CoreConstants.NULL_KEY[0] && time == CoreConstants.NULL_KEY[1] && id == CoreConstants.NULL_KEY[2]) {
            callback.on(null);
            return;
        }
        final MWGResolver selfPointer = this;
        final Chunk cached = this._space.getAndMark(type, world, time, id);
        if (cached != null) {
            callback.on(cached);
        } else {
            final Buffer buffer = selfPointer._graph.newBuffer();
            BufferBuilder.keyToBuffer(buffer, type, world, time, id);
            this._storage.get(buffer, new Callback<Buffer>() {
                @Override
                public void on(Buffer payloads) {
                    buffer.free();
                    Chunk result = null;
                    final BufferIterator it = payloads.iterator();
                    if (it.hasNext()) {
                        final Buffer view = it.next();
                        if (view.length() > 0) {
                            result = selfPointer._space.create(type, world, time, id, view, null);
                            selfPointer._space.putAndMark(result);
                        }
                    }
                    payloads.free();
                    callback.on(result);
                }
            });

        }
    }

    private void getOrLoadAndMarkAll(final byte[] types, final long[] keys, final Callback<Chunk[]> callback) {
        int nbKeys = keys.length / KEY_SIZE;
        final boolean[] toLoadIndexes = new boolean[nbKeys];
        int nbElem = 0;
        final Chunk[] result = new Chunk[nbKeys];
        for (int i = 0; i < nbKeys; i++) {
            if (keys[i * KEY_SIZE] == CoreConstants.NULL_KEY[0] && keys[i * KEY_SIZE + 1] == CoreConstants.NULL_KEY[1] && keys[i * KEY_SIZE + 2] == CoreConstants.NULL_KEY[2]) {
                toLoadIndexes[i] = false;
                result[i] = null;
            } else {
                result[i] = this._space.getAndMark(types[i], keys[i * KEY_SIZE], keys[i * KEY_SIZE + 1], keys[i * KEY_SIZE + 2]);
                if (result[i] == null) {
                    toLoadIndexes[i] = true;
                    nbElem++;
                } else {
                    toLoadIndexes[i] = false;
                }
            }
        }
        if (nbElem == 0) {
            callback.on(result);
        } else {
            final Buffer keysToLoad = _graph.newBuffer();
            final int[] reverseIndex = new int[nbElem];
            int lastInsertedIndex = 0;
            for (int i = 0; i < nbKeys; i++) {
                if (toLoadIndexes[i]) {
                    reverseIndex[lastInsertedIndex] = i;
                    if (lastInsertedIndex != 0) {
                        keysToLoad.write(CoreConstants.BUFFER_SEP);
                    }
                    BufferBuilder.keyToBuffer(keysToLoad, types[i], keys[i * KEY_SIZE], keys[i * KEY_SIZE + 1], keys[i * KEY_SIZE + 2]);
                    lastInsertedIndex = lastInsertedIndex + 1;
                }
            }
            final MWGResolver selfPointer = this;
            this._storage.get(keysToLoad, new Callback<Buffer>() {
                @Override
                public void on(Buffer fromDbBuffers) {
                    keysToLoad.free();
                    BufferIterator it = fromDbBuffers.iterator();
                    int i = 0;
                    while (it.hasNext()) {
                        int reversedIndex = reverseIndex[i];
                        final Buffer view = it.next();
                        if (view.length() > 0) {
                            result[reversedIndex] = selfPointer._space.create(types[reversedIndex], keys[reversedIndex * KEY_SIZE], keys[reversedIndex * KEY_SIZE + 1], keys[reversedIndex * KEY_SIZE + 2], view, null);
                        } else {
                            result[reversedIndex] = null;
                        }
                        i++;
                    }
                    fromDbBuffers.free();
                    callback.on(result);
                }
            });
        }
    }

    /*

    @Override
    public NodeState newState(org.mwg.Node node, long world, long time) {
        //Retrieve Node needed chunks
        final WorldOrderChunk nodeWorldOrder = (WorldOrderChunk) this._space.getAndMark(ChunkType.WORLD_ORDER_CHUNK, CoreConstants.NULL_LONG, CoreConstants.NULL_LONG, node.id());
        if (nodeWorldOrder == null) {
            return null;
        }
        //SOMETHING WILL MOVE HERE ANYWAY SO WE SYNC THE OBJECT, even for dePhasing read only objects because they can be unaligned after
        nodeWorldOrder.lock();
        //OK NOW WE HAVE THE TOKEN globally FOR the node ID
        StateChunk resultState = null;
        try {
            AbstractNode castedNode = (AbstractNode) node;
            //protection against deleted Node
            long[] previousResolveds = castedNode._previousResolveds.get();
            if (previousResolveds == null) {
                throw new RuntimeException(CoreConstants.DEAD_NODE_ERROR);
            }

            if (time < previousResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_MAGIC]) {
                throw new RuntimeException("New state cannot be used to create state before the previously resolved state");
            }

            long nodeId = node.id();

            //check if anything as moved
            if (previousResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX] == world && previousResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_INDEX] == time) {
                //no new state to create
                resultState = (StateChunk) this._space.getAndMark(ChunkType.STATE_CHUNK, world, time, nodeId);
                this._space.unmarkChunk(resultState);
                this._space.unmarkChunk(nodeWorldOrder);
                return resultState;
            }

            //first we create and insert the empty state
            Chunk resultState_0 = this._space.create(ChunkType.STATE_CHUNK, world, time, nodeId, null, null);
            resultState = (StateChunk) _space.putAndMark(resultState_0);
            if (resultState_0 != resultState) {
                _space.freeChunk(resultState_0);
            }

            if (previousResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX] == world || nodeWorldOrder.get(world) != CoreConstants.NULL_LONG) {

                //let's go for the resolution now
                TimeTreeChunk nodeSuperTimeTree = (TimeTreeChunk) this._space.getAndMark(ChunkType.TIME_TREE_CHUNK, previousResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX], CoreConstants.NULL_LONG, nodeId);
                if (nodeSuperTimeTree == null) {
                    this._space.unmarkChunk(nodeWorldOrder);
                    return null;
                }
                TimeTreeChunk nodeTimeTree = (TimeTreeChunk) this._space.getAndMark(ChunkType.TIME_TREE_CHUNK, previousResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX], previousResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX], nodeId);
                if (nodeTimeTree == null) {
                    this._space.unmarkChunk(nodeSuperTimeTree);
                    this._space.unmarkChunk(nodeWorldOrder);
                    return null;
                }

                //manage super tree here
                long superTreeSize = nodeSuperTimeTree.size();
                long threshold = CoreConstants.SCALE_1 * 2;
                if (superTreeSize > threshold) {
                    threshold = CoreConstants.SCALE_2 * 2;
                }
                if (superTreeSize > threshold) {
                    threshold = CoreConstants.SCALE_3 * 2;
                }
                if (superTreeSize > threshold) {
                    threshold = CoreConstants.SCALE_4 * 2;
                }
                nodeTimeTree.insert(time);
                if (nodeTimeTree.size() == threshold) {
                    final long[] medianPoint = {-1};
                    //we iterate over the tree selectWithout boundaries for values, but selectWith boundaries for number of collected times
                    nodeTimeTree.range(Constants.BEGINNING_OF_TIME, Constants.END_OF_TIME, nodeTimeTree.size() / 2, new TreeWalker() {
                        @Override
                        public void elem(long t) {
                            medianPoint[0] = t;
                        }
                    });

                    TimeTreeChunk rightTree_0 = (TimeTreeChunk) this._space.create(ChunkType.TIME_TREE_CHUNK, world, medianPoint[0], nodeId, null, null);
                    TimeTreeChunk rightTree = (TimeTreeChunk) this._space.putAndMark(rightTree_0);
                    if (rightTree_0 != rightTree) {
                        this._space.freeChunk(rightTree_0);
                    }

                    //TODO second iterate that can be avoided, however we need the median point to create the right tree
                    //we iterate over the tree selectWithout boundaries for values, but selectWith boundaries for number of collected times
                    final TimeTreeChunk finalRightTree = rightTree;
                    //rang iterate fromVar the end of the tree
                    nodeTimeTree.range(Constants.BEGINNING_OF_TIME, Constants.END_OF_TIME, nodeTimeTree.size() / 2, new TreeWalker() {
                        @Override
                        public void elem(long t) {
                            finalRightTree.insert(t);
                        }
                    });
                    nodeSuperTimeTree.insert(medianPoint[0]);
                    //remove times insert in the right tree
                    nodeTimeTree.clearAt(medianPoint[0]);

                    //ok ,now manage marks
                    if (time < medianPoint[0]) {

                        this._space.unmarkChunk(rightTree);
                        this._space.unmarkChunk(nodeSuperTimeTree);
                        this._space.unmarkChunk(nodeTimeTree);

                        long[] newResolveds = new long[6];
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX] = world;

                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = previousResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX];
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_INDEX] = time;
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = nodeSuperTimeTree.magic();
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                        castedNode._previousResolveds.set(newResolveds);
                    } else {

                        //double unMark current nodeTimeTree
                        this._space.unmarkChunk(nodeTimeTree);
                        this._space.unmarkChunk(nodeTimeTree);
                        //unmark node superTimeTree
                        this._space.unmarkChunk(nodeSuperTimeTree);

                        //let's store the new state if necessary
                        long[] newResolveds = new long[6];
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX] = world;
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = medianPoint[0];
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_INDEX] = time;
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = rightTree.magic();
                        newResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                        castedNode._previousResolveds.set(newResolveds);
                    }
                } else {
                    //update the state cache selectWithout superTree modification
                    long[] newResolveds = new long[6];
                    //previously resolved
                    newResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX] = world;
                    newResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = previousResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX];
                    newResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_INDEX] = time;
                    //previously magics
                    newResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                    newResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = nodeSuperTimeTree.magic();
                    newResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_MAGIC] = nodeTimeTree.magic();
                    castedNode._previousResolveds.set(newResolveds);

                    this._space.unmarkChunk(nodeSuperTimeTree);
                    this._space.unmarkChunk(nodeTimeTree);
                }
            } else {

                //create a new node superTimeTree
                TimeTreeChunk newSuperTimeTree_0 = (TimeTreeChunk) this._space.create(ChunkType.TIME_TREE_CHUNK, world, CoreConstants.NULL_LONG, nodeId, null, null);
                TimeTreeChunk newSuperTimeTree = (TimeTreeChunk) this._space.putAndMark(newSuperTimeTree_0);
                if (newSuperTimeTree != newSuperTimeTree_0) {
                    this._space.freeChunk(newSuperTimeTree_0);
                }
                newSuperTimeTree.insert(time);

                //create a new node timeTree
                TimeTreeChunk newTimeTree_0 = (TimeTreeChunk) this._space.create(ChunkType.TIME_TREE_CHUNK, world, time, nodeId, null, null);
                TimeTreeChunk newTimeTree = (TimeTreeChunk) this._space.putAndMark(newTimeTree_0);
                if (newTimeTree != newTimeTree_0) {
                    this._space.freeChunk(newTimeTree_0);
                }
                newTimeTree.insert(time);

                //insert into node world order
                nodeWorldOrder.put(world, time);

                //let's store the new state if necessary
                long[] newResolveds = new long[6];
                //previously resolved
                newResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX] = world;
                newResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX] = time;
                newResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_INDEX] = time;
                //previously magics
                newResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_MAGIC] = nodeWorldOrder.magic();
                newResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_MAGIC] = newSuperTimeTree.magic();
                newResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_MAGIC] = newTimeTree.magic();
                castedNode._previousResolveds.set(newResolveds);

                //unMark previous super Tree
                _space.unmark(ChunkType.TIME_TREE_CHUNK, previousResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX], Constants.NULL_LONG, nodeId);
                //unMark previous time Tree
                _space.unmark(ChunkType.TIME_TREE_CHUNK, previousResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX], previousResolveds[CoreConstants.PREVIOUS_RESOLVED_SUPER_TIME_INDEX], nodeId);

            }

            //unMark previous state, for the newly created one
            _space.unmark(ChunkType.STATE_CHUNK, previousResolveds[CoreConstants.PREVIOUS_RESOLVED_WORLD_INDEX], previousResolveds[CoreConstants.PREVIOUS_RESOLVED_TIME_INDEX], nodeId);
            _space.unmarkChunk(nodeWorldOrder);

        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            nodeWorldOrder.unlock();
        }
        return (NodeState) resultState;
    }*/

    @Override
    public final NodeState resolveState(final org.mwg.Node node) {
        return internal_resolveState(node, true);
    }

    private StateChunk internal_resolveState(final org.mwg.Node node, final boolean safe) {
        final AbstractNode castedNode = (AbstractNode) node;
        StateChunk stateResult = null;
        if (safe) {
            castedNode.cacheLock();
        }
        if (castedNode._dead) {
            if (safe) {
                castedNode.cacheUnlock();
            }
            throw new RuntimeException(CoreConstants.DEAD_NODE_ERROR + " node id: " + node.id());
        }
        /* OPTIMIZATION #1: NO DEPHASING */
        if (castedNode._world_magic == -1 && castedNode._time_magic == -1 && castedNode._super_time_magic == -1) {
            stateResult = (StateChunk) this._space.getByIndex(castedNode._index_stateChunk);
        } else {
            /* OPTIMIZATION #2: SAME DEPHASING */
            final WorldOrderChunk nodeWorldOrder = (WorldOrderChunk) this._space.getByIndex(castedNode._index_worldOrder);
            TimeTreeChunk nodeSuperTimeTree = (TimeTreeChunk) this._space.getByIndex(castedNode._index_superTimeTree);
            TimeTreeChunk nodeTimeTree = (TimeTreeChunk) this._space.getByIndex(castedNode._index_timeTree);
            if (nodeWorldOrder != null && nodeSuperTimeTree != null && nodeTimeTree != null) {
                if (castedNode._world_magic == nodeWorldOrder.magic() && castedNode._super_time_magic == nodeSuperTimeTree.magic() && castedNode._time_magic == nodeTimeTree.magic()) {
                    stateResult = (StateChunk) this._space.getByIndex(castedNode._index_stateChunk);
                } else {
                    /* NOMINAL CASE, LET'S RESOLVE AGAIN */
                    if (safe) {
                        nodeWorldOrder.lock();
                    }
                    final long nodeTime = castedNode.time();
                    final long nodeId = castedNode.id();
                    final long nodeWorld = castedNode.world();
                    //Common case, we have to traverseIndex World Order and Time chunks
                    final long resolvedWorld = resolve_world(globalWorldOrderChunk, nodeWorldOrder, nodeTime, nodeWorld);
                    if (resolvedWorld != nodeSuperTimeTree.world()) {
                        //we have to update the superTree
                        final TimeTreeChunk tempNodeSuperTimeTree = (TimeTreeChunk) this._space.getAndMark(ChunkType.TIME_TREE_CHUNK, resolvedWorld, CoreConstants.NULL_LONG, nodeId);
                        if (tempNodeSuperTimeTree != null) {
                            _space.unmarkChunk(nodeSuperTimeTree);
                            nodeSuperTimeTree = tempNodeSuperTimeTree;
                        }
                    }
                    long resolvedSuperTime = nodeSuperTimeTree.previousOrEqual(nodeTime);
                    if (resolvedSuperTime != nodeTimeTree.time()) {
                        //we have to update the timeTree
                        final TimeTreeChunk tempNodeTimeTree = (TimeTreeChunk) this._space.getAndMark(ChunkType.TIME_TREE_CHUNK, resolvedWorld, resolvedSuperTime, nodeId);
                        if (tempNodeTimeTree != null) {
                            _space.unmarkChunk(nodeTimeTree);
                            nodeTimeTree = tempNodeTimeTree;
                        }
                    }
                    final long resolvedTime = nodeTimeTree.previousOrEqual(nodeTime);
                    castedNode._time_magic = nodeTimeTree.magic();
                    castedNode._super_time_magic = nodeSuperTimeTree.magic();
                    stateResult = (StateChunk) this._space.getByIndex(castedNode._index_stateChunk);
                    if (resolvedWorld != stateResult.world() || resolvedTime != stateResult.time()) {
                        final StateChunk tempNodeState = (StateChunk) this._space.getAndMark(ChunkType.STATE_CHUNK, resolvedWorld, resolvedTime, nodeId);
                        if (tempNodeState != null) {
                            this._space.unmarkChunk(stateResult);
                            stateResult = tempNodeState;
                            castedNode._index_stateChunk = stateResult.index();
                        }
                    }
                    if (safe) {
                        nodeWorldOrder.unlock();
                    }
                }
            }
        }
        if (safe) {
            castedNode.cacheUnlock();
        }
        return stateResult;
    }

    @Override
    public final NodeState alignState(final org.mwg.Node node) {
        final AbstractNode castedNode = (AbstractNode) node;
        castedNode.cacheLock();
        if (castedNode._dead) {
            castedNode.cacheUnlock();
            throw new RuntimeException(CoreConstants.DEAD_NODE_ERROR + " node id: " + node.id());
        }
        //OPTIMIZATION #1: NO DEPHASING
        if (castedNode._world_magic == -1 && castedNode._time_magic == -1 && castedNode._super_time_magic == -1) {
            final StateChunk currentEntry = (StateChunk) this._space.getByIndex(castedNode._index_stateChunk);
            if (currentEntry != null) {
                castedNode.cacheUnlock();
                return currentEntry;
            }
        }
        //NOMINAL CASE
        final WorldOrderChunk nodeWorldOrder = (WorldOrderChunk) this._space.getByIndex(castedNode._index_worldOrder);
        if (nodeWorldOrder == null) {
            castedNode.cacheUnlock();
            return null;
        }

        nodeWorldOrder.lock();

        final long nodeWorld = node.world();
        final long nodeTime = node.time();
        final long nodeId = node.id();

        //Get the previous StateChunk
        final StateChunk previouStateChunk = internal_resolveState(node, false);
        if (castedNode._world_magic == -1 && castedNode._time_magic == -1 && castedNode._super_time_magic == -1) {
            //it has been already rePhased, just return
            nodeWorldOrder.unlock();
            castedNode.cacheUnlock();
            return previouStateChunk;
        }

        final StateChunk clonedState = (StateChunk) this._space.create(ChunkType.STATE_CHUNK, nodeWorld, nodeTime, nodeId, null, previouStateChunk);
        this._space.putAndMark(clonedState);

        castedNode._world_magic = -1;
        castedNode._super_time_magic = -1;
        castedNode._time_magic = -1;

        castedNode._index_stateChunk = clonedState.index();
        _space.unmarkChunk(previouStateChunk);

        if (previouStateChunk.world() == nodeWorld || nodeWorldOrder.get(nodeWorld) != CoreConstants.NULL_LONG) {
            final TimeTreeChunk superTimeTree = (TimeTreeChunk) this._space.getByIndex(castedNode._index_superTimeTree);
            final TimeTreeChunk timeTree = (TimeTreeChunk) this._space.getByIndex(castedNode._index_timeTree);
            //manage super tree here
            long superTreeSize = superTimeTree.size();
            long threshold = CoreConstants.SCALE_1 * 2;
            if (superTreeSize > threshold) {
                threshold = CoreConstants.SCALE_2 * 2;
            }
            if (superTreeSize > threshold) {
                threshold = CoreConstants.SCALE_3 * 2;
            }
            if (superTreeSize > threshold) {
                threshold = CoreConstants.SCALE_4 * 2;
            }
            timeTree.insert(nodeTime);
            if (timeTree.size() == threshold) {
                final long[] medianPoint = {-1};
                //we iterate over the tree selectWithout boundaries for values, but selectWith boundaries for number of collected times
                timeTree.range(CoreConstants.BEGINNING_OF_TIME, CoreConstants.END_OF_TIME, timeTree.size() / 2, new TreeWalker() {
                    @Override
                    public void elem(long t) {
                        medianPoint[0] = t;
                    }
                });
                TimeTreeChunk rightTree = (TimeTreeChunk) this._space.create(ChunkType.TIME_TREE_CHUNK, nodeWorld, medianPoint[0], nodeId, null, null);
                rightTree = (TimeTreeChunk) this._space.putAndMark(rightTree);
                //TODO second iterate that can be avoided, however we need the median point to create the right tree
                //we iterate over the tree selectWithout boundaries for values, but selectWith boundaries for number of collected times
                final TimeTreeChunk finalRightTree = rightTree;
                //rang iterate fromVar the end of the tree
                timeTree.range(CoreConstants.BEGINNING_OF_TIME, CoreConstants.END_OF_TIME, timeTree.size() / 2, new TreeWalker() {
                    @Override
                    public void elem(long t) {
                        finalRightTree.unsafe_insert(t);
                    }
                });
                _space.declareDirty(finalRightTree);
                superTimeTree.insert(medianPoint[0]);
                //remove times insert in the right tree
                timeTree.clearAt(medianPoint[0]);
                //ok ,now manage marks
                if (nodeTime < medianPoint[0]) {
                    _space.unmarkChunk(rightTree);
                } else {
                    castedNode._index_timeTree = finalRightTree.index();
                    _space.unmarkChunk(timeTree);
                }
            }
        } else {
            //create a new node superTimeTree
            TimeTreeChunk newSuperTimeTree_0 = (TimeTreeChunk) this._space.create(ChunkType.TIME_TREE_CHUNK, nodeWorld, CoreConstants.NULL_LONG, nodeId, null, null);
            TimeTreeChunk newSuperTimeTree = (TimeTreeChunk) this._space.putAndMark(newSuperTimeTree_0);
            if (newSuperTimeTree_0 != newSuperTimeTree) {
                this._space.freeChunk(newSuperTimeTree_0);
            }
            newSuperTimeTree.insert(nodeTime);
            //create a new node timeTree
            TimeTreeChunk newTimeTree_0 = (TimeTreeChunk) this._space.create(ChunkType.TIME_TREE_CHUNK, nodeWorld, nodeTime, nodeId, null, null);
            TimeTreeChunk newTimeTree = (TimeTreeChunk) this._space.putAndMark(newTimeTree_0);
            if (newTimeTree_0 != newTimeTree) {
                this._space.freeChunk(newTimeTree_0);
            }
            newTimeTree.insert(nodeTime);
            //insert into node world order
            nodeWorldOrder.put(nodeWorld, nodeTime);
            //let's store the new state if necessary

            _space.unmarkByIndex(castedNode._index_timeTree);
            _space.unmarkByIndex(castedNode._index_superTimeTree);

            castedNode._index_timeTree = newTimeTree.index();
            castedNode._index_superTimeTree = newSuperTimeTree.index();
        }

        nodeWorldOrder.unlock();
        castedNode.cacheUnlock();
        return clonedState;
    }

    @Override
    public NodeState newState(Node node, long world, long time) {
        final AbstractNode castedNode = (AbstractNode) node;
        NodeState resolved;
        castedNode.cacheLock();

        AbstractNode fakeNode = new CoreNode(world, time, node.id(), node.graph());
        fakeNode._index_worldOrder = castedNode._index_worldOrder;
        fakeNode._index_superTimeTree = castedNode._index_superTimeTree;
        fakeNode._index_timeTree = castedNode._index_timeTree;
        fakeNode._index_stateChunk = castedNode._index_stateChunk;

        fakeNode._time_magic = castedNode._time_magic;
        fakeNode._super_time_magic = castedNode._super_time_magic;
        fakeNode._world_magic = castedNode._world_magic;

        resolved = alignState(fakeNode);

        castedNode._index_worldOrder = fakeNode._index_worldOrder;
        castedNode._index_superTimeTree = fakeNode._index_superTimeTree;
        castedNode._index_timeTree = fakeNode._index_timeTree;
        castedNode._index_stateChunk = fakeNode._index_stateChunk;

        castedNode._time_magic = fakeNode._time_magic;
        castedNode._super_time_magic = fakeNode._super_time_magic;
        castedNode._world_magic = fakeNode._world_magic;

        castedNode.cacheUnlock();

        return resolved;
    }

    @Override
    public void resolveTimepoints(final org.mwg.Node node, final long beginningOfSearch, final long endOfSearch, final Callback<long[]> callback) {
        final MWGResolver selfPointer = this;
        getOrLoadAndMark(ChunkType.WORLD_ORDER_CHUNK, 0, 0, node.id(), new Callback<Chunk>() {
            @Override
            public void on(Chunk resolved) {
                if (resolved == null) {
                    callback.on(new long[0]);
                    return;
                }
                final WorldOrderChunk objectWorldOrder = (WorldOrderChunk) resolved;
                //worlds collector
                final int[] collectionSize = {CoreConstants.MAP_INITIAL_CAPACITY};
                final long[][] collectedWorlds = {new long[collectionSize[0]]};
                int collectedIndex = 0;
                long currentWorld = node.world();
                while (currentWorld != CoreConstants.NULL_LONG) {
                    long divergenceTimepoint = objectWorldOrder.get(currentWorld);
                    if (divergenceTimepoint != CoreConstants.NULL_LONG) {
                        if (divergenceTimepoint <= beginningOfSearch) {
                            //take the first one before leaving
                            collectedWorlds[0][collectedIndex] = currentWorld;
                            collectedIndex++;
                            break;
                        } else if (divergenceTimepoint > endOfSearch) {
                            //next round, go to parent world
                            currentWorld = selfPointer.globalWorldOrderChunk.get(currentWorld);
                        } else {
                            //that's fit, add to search
                            collectedWorlds[0][collectedIndex] = currentWorld;
                            collectedIndex++;
                            if (collectedIndex == collectionSize[0]) {
                                //reallocate
                                long[] temp_collectedWorlds = new long[collectionSize[0] * 2];
                                System.arraycopy(collectedWorlds[0], 0, temp_collectedWorlds, 0, collectionSize[0]);
                                collectedWorlds[0] = temp_collectedWorlds;
                                collectionSize[0] = collectionSize[0] * 2;
                            }
                            //go to parent
                            currentWorld = selfPointer.globalWorldOrderChunk.get(currentWorld);
                        }
                    } else {
                        //go to parent
                        currentWorld = selfPointer.globalWorldOrderChunk.get(currentWorld);
                    }
                }
                //create request concat keys
                selfPointer.resolveTimepointsFromWorlds(selfPointer.globalWorldOrderChunk, objectWorldOrder, node, beginningOfSearch, endOfSearch, collectedWorlds[0], collectedIndex, callback);
            }
        });
    }

    private void resolveTimepointsFromWorlds(final WorldOrderChunk globalWorldOrder, final WorldOrderChunk objectWorldOrder, final org.mwg.Node node, final long beginningOfSearch, final long endOfSearch, final long[] collectedWorlds, final int collectedWorldsSize, final Callback<long[]> callback) {
        final MWGResolver selfPointer = this;
        final long[] timeTreeKeys = new long[collectedWorldsSize * 3];
        final byte[] types = new byte[collectedWorldsSize];
        for (int i = 0; i < collectedWorldsSize; i++) {
            timeTreeKeys[i * 3] = collectedWorlds[i];
            timeTreeKeys[i * 3 + 1] = CoreConstants.NULL_LONG;
            timeTreeKeys[i * 3 + 2] = node.id();
            types[i] = ChunkType.TIME_TREE_CHUNK;
        }
        getOrLoadAndMarkAll(types, timeTreeKeys, new Callback<Chunk[]>() {
            @Override
            public void on(final Chunk[] superTimeTrees) {
                if (superTimeTrees == null) {
                    selfPointer._space.unmarkChunk(objectWorldOrder);
                    callback.on(new long[0]);
                } else {
                    //time collector
                    final int[] collectedSize = {CoreConstants.MAP_INITIAL_CAPACITY};
                    final long[][] collectedSuperTimes = {new long[collectedSize[0]]};
                    final long[][] collectedSuperTimesAssociatedWorlds = {new long[collectedSize[0]]};
                    final int[] insert_index = {0};

                    long previousDivergenceTime = endOfSearch;
                    for (int i = 0; i < collectedWorldsSize; i++) {
                        final TimeTreeChunk timeTree = (TimeTreeChunk) superTimeTrees[i];
                        if (timeTree != null) {
                            long currentDivergenceTime = objectWorldOrder.get(collectedWorlds[i]);
                            //if (currentDivergenceTime < beginningOfSearch) {
                            //    currentDivergenceTime = beginningOfSearch;
                            //}
                            final long finalPreviousDivergenceTime = previousDivergenceTime;
                            timeTree.range(currentDivergenceTime, previousDivergenceTime, CoreConstants.END_OF_TIME, new TreeWalker() {
                                @Override
                                public void elem(long t) {
                                    if (t != finalPreviousDivergenceTime) {
                                        collectedSuperTimes[0][insert_index[0]] = t;
                                        collectedSuperTimesAssociatedWorlds[0][insert_index[0]] = timeTree.world();
                                        insert_index[0]++;
                                        if (collectedSize[0] == insert_index[0]) {
                                            //reallocate
                                            long[] temp_collectedSuperTimes = new long[collectedSize[0] * 2];
                                            long[] temp_collectedSuperTimesAssociatedWorlds = new long[collectedSize[0] * 2];
                                            System.arraycopy(collectedSuperTimes[0], 0, temp_collectedSuperTimes, 0, collectedSize[0]);
                                            System.arraycopy(collectedSuperTimesAssociatedWorlds[0], 0, temp_collectedSuperTimesAssociatedWorlds, 0, collectedSize[0]);

                                            collectedSuperTimes[0] = temp_collectedSuperTimes;
                                            collectedSuperTimesAssociatedWorlds[0] = temp_collectedSuperTimesAssociatedWorlds;

                                            collectedSize[0] = collectedSize[0] * 2;
                                        }
                                    }
                                }
                            });
                            previousDivergenceTime = currentDivergenceTime;
                        }
                        selfPointer._space.unmarkChunk(timeTree);
                    }
                    //now we have superTimes, lets convert them to all times
                    selfPointer.resolveTimepointsFromSuperTimes(objectWorldOrder, node, beginningOfSearch, endOfSearch, collectedSuperTimesAssociatedWorlds[0], collectedSuperTimes[0], insert_index[0], callback);
                }
            }
        });
    }

    private void resolveTimepointsFromSuperTimes(final WorldOrderChunk objectWorldOrder, final org.mwg.Node node, final long beginningOfSearch, final long endOfSearch, final long[] collectedWorlds, final long[] collectedSuperTimes, final int collectedSize, final Callback<long[]> callback) {
        final MWGResolver selfPointer = this;
        final long[] timeTreeKeys = new long[collectedSize * 3];
        final byte[] types = new byte[collectedSize];
        for (int i = 0; i < collectedSize; i++) {
            timeTreeKeys[i * 3] = collectedWorlds[i];
            timeTreeKeys[i * 3 + 1] = collectedSuperTimes[i];
            timeTreeKeys[i * 3 + 2] = node.id();
            types[i] = ChunkType.TIME_TREE_CHUNK;
        }
        getOrLoadAndMarkAll(types, timeTreeKeys, new Callback<Chunk[]>() {
            @Override
            public void on(Chunk[] timeTrees) {
                if (timeTrees == null) {
                    selfPointer._space.unmarkChunk(objectWorldOrder);
                    callback.on(new long[0]);
                } else {
                    //time collector
                    final int[] collectedTimesSize = {CoreConstants.MAP_INITIAL_CAPACITY};
                    final long[][] collectedTimes = {new long[collectedTimesSize[0]]};
                    final int[] insert_index = {0};
                    long previousDivergenceTime = endOfSearch;
                    for (int i = 0; i < collectedSize; i++) {
                        final TimeTreeChunk timeTree = (TimeTreeChunk) timeTrees[i];
                        if (timeTree != null) {
                            long currentDivergenceTime = objectWorldOrder.get(collectedWorlds[i]);
                            if (currentDivergenceTime < beginningOfSearch) {
                                currentDivergenceTime = beginningOfSearch;
                            }
                            final long finalPreviousDivergenceTime = previousDivergenceTime;
                            timeTree.range(currentDivergenceTime, previousDivergenceTime, CoreConstants.END_OF_TIME, new TreeWalker() {
                                @Override
                                public void elem(long t) {
                                    if (t != finalPreviousDivergenceTime) {
                                        collectedTimes[0][insert_index[0]] = t;
                                        insert_index[0]++;
                                        if (collectedTimesSize[0] == insert_index[0]) {
                                            //reallocate
                                            long[] temp_collectedTimes = new long[collectedTimesSize[0] * 2];
                                            System.arraycopy(collectedTimes[0], 0, temp_collectedTimes, 0, collectedTimesSize[0]);
                                            collectedTimes[0] = temp_collectedTimes;
                                            collectedTimesSize[0] = collectedTimesSize[0] * 2;
                                        }
                                    }
                                }
                            });
                            if (i < collectedSize - 1) {
                                if (collectedWorlds[i + 1] != collectedWorlds[i]) {
                                    //world overriding semantic
                                    previousDivergenceTime = currentDivergenceTime;
                                }
                            }
                        }
                        selfPointer._space.unmarkChunk(timeTree);
                    }
                    //now we have times
                    if (insert_index[0] != collectedTimesSize[0]) {
                        long[] tempTimeline = new long[insert_index[0]];
                        System.arraycopy(collectedTimes[0], 0, tempTimeline, 0, insert_index[0]);
                        collectedTimes[0] = tempTimeline;
                    }
                    selfPointer._space.unmarkChunk(objectWorldOrder);
                    callback.on(collectedTimes[0]);
                }
            }
        });
    }

    @Override
    public long stringToHash(String name, boolean insertIfNotExists) {
        long hash = DataHasher.hash(name);
        if (insertIfNotExists) {
            StringLongMap dictionaryIndex = (StringLongMap) this.dictionary.get(0);
            if (dictionaryIndex == null) {
                dictionaryIndex = (StringLongMap) this.dictionary.getOrCreate(0, Type.STRING_TO_LONG_MAP);
            }
            if (!dictionaryIndex.containsHash(hash)) {
                dictionaryIndex.put(name, hash);
            }
        }
        return hash;
    }

    @Override
    public String hashToString(long key) {
        final StringLongMap dictionaryIndex = (StringLongMap) this.dictionary.get(0);
        if (dictionaryIndex != null) {
            return dictionaryIndex.getByHash(key);
        }
        return null;
    }

}
