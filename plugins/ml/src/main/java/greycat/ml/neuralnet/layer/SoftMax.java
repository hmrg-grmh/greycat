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
package greycat.ml.neuralnet.layer;

import greycat.Type;
import greycat.ml.neuralnet.activation.Activation;
import greycat.ml.neuralnet.process.ExMatrix;
import greycat.ml.neuralnet.process.ProcessGraph;
import greycat.struct.EStruct;
import greycat.struct.matrix.RandomInterface;

public class SoftMax implements Layer {
    private static String INPUTS = "inputs";
    private EStruct host;

    SoftMax(EStruct hostnode) {
        this.host = hostnode;
    }

    @Override
    public Layer create(int inputs, int outputs, int activationUnit, double[] activationParams) {
        //First always set the type
        if (inputs != outputs) {
            throw new RuntimeException("SoftMax is stateless, inputs and outputs should be the same size, otherwise use LinearSoftMax");
        }
        host.set(INPUTS, Type.INT, inputs);
        host.set(Layers.LAYER_TYPE, Type.INT, Layers.SOFTMAX_LAYER);
        return this;
    }

    @Override
    public Layer init(int weightInitType, RandomInterface random, double std) {
        return this;
    }

    @Override
    public ExMatrix forward(ExMatrix input, ProcessGraph g) {
        return g.softmax(input);
    }


    @Override
    public ExMatrix[] getLayerParameters() {
        return new ExMatrix[]{};
    }

    @Override
    public void resetState() {

    }

    @Override
    public int inputDimensions() {
        return (int) host.get(INPUTS);
    }

    @Override
    public int outputDimensions() {
        return (int) host.get(INPUTS);
    }

    @Override
    public Activation getActivation() {
        return null;
    }

    @Override
    public void print(boolean details) {
        System.out.println("Layer SoftMax " + inputDimensions() + " -> " + outputDimensions());
    }
}
