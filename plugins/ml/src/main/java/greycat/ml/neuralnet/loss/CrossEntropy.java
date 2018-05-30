/**
 * Copyright 2017 The GreyCat Authors.  All rights reserved.
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
package greycat.ml.neuralnet.loss;

import greycat.ml.neuralnet.process.ExMatrix;
import greycat.struct.DMatrix;
import greycat.struct.matrix.MatrixOps;
import greycat.struct.matrix.VolatileDMatrix;

public class CrossEntropy implements Loss {

    private static double EPS = 1e-15;
    private static CrossEntropy static_unit = null;

    public static Loss instance() {
        if (static_unit == null) {
            static_unit = new CrossEntropy();
        }
        return static_unit;
    }

    @Override
    public void backward(ExMatrix actualOutput, ExMatrix targetOutput) {

    }

    @Override
    public DMatrix forward(DMatrix actualOutput, DMatrix targetOutput) {
        MatrixOps.testDim(actualOutput, targetOutput);
        DMatrix res = VolatileDMatrix.empty(actualOutput.rows(), actualOutput.columns());

        int len = targetOutput.length();
        for (int i = 0; i < len; i++) {
            res.unsafeSet(i, -targetOutput.unsafeGet(i) * Math.log(Math.max(actualOutput.unsafeGet(i), EPS)));
        }
        return res;
    }
}
