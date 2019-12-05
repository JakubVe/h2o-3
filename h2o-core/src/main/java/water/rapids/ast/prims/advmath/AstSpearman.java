package water.rapids.ast.prims.advmath;

import water.DKV;
import water.MRTask;
import water.Scope;
import water.Value;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.rapids.Env;
import water.rapids.Val;
import water.rapids.ast.AstPrimitive;
import water.rapids.ast.AstRoot;
import water.rapids.vals.ValNum;

public class AstSpearman extends AstPrimitive<AstSpearman> {
  @Override
  public int nargs() {
    return 1 + 3; // Frame ID and ID of two numerical vectors to calculate SCC on.
  }

  @Override
  public String[] args() {
    return new String[]{"frame", "first_column", "second_column"};
  }

  @Override
  public Val apply(Env env, Env.StackHelp stk, AstRoot[] asts) {
    final Value value = DKV.get(asts[1].str());
    if (!value.isFrame()) {
      throw new IllegalArgumentException(String.format("The given key '%s' is not a frame.", asts[1]));
    }

    final Frame originalUnsortedFrame = value.get(Frame.class);
    final int vecIdX = originalUnsortedFrame.find(asts[2].exec(env).getStr());
    final int vecIdY = originalUnsortedFrame.find(asts[3].exec(env).getStr());

    Scope.enter();

    Frame sortedX = new Frame(originalUnsortedFrame.vec(vecIdX).makeCopy());
    Frame sortedY = new Frame(originalUnsortedFrame.vec(vecIdY).makeCopy());
    Scope.track(sortedX);

    if (!sortedX.vec(0).isCategorical()) {
      sortedX.label("label");
      sortedX = sortedX.sort(new int[]{0});
      Scope.track(sortedX);

    }

    if (!sortedY.vec(0).isCategorical()) {
      sortedY.label("label");
      sortedY = sortedY.sort(new int[]{0});
      Scope.track(sortedY);
    }

    assert sortedX.numRows() == sortedY.numRows();
    final Vec orderX = Vec.makeZero(sortedX.numRows());
    final Vec orderY = Vec.makeZero(sortedY.numRows());

    final Vec xLabel = sortedX.vec("label") == null ? sortedX.vec(0) : sortedX.vec("label");
    final Vec yLabel = sortedY.vec("label") == null ? sortedY.vec(0) : sortedY.vec("label");
    Scope.track(xLabel);
    Scope.track(yLabel);

    for (int i = 0; i < orderX.length(); i++) {
      orderX.set(xLabel.at8(i) - 1, i + 1);
      orderY.set(yLabel.at8(i) - 1, i + 1);
    }
    final SpearmanCorrelationCoefficientTask spearman = new SpearmanCorrelationCoefficientTask(orderX.mean(), orderY.mean())
            .doAll(orderX, orderY);
    Scope.exit();
    return new ValNum(spearman.getSpearmanCorrelationCoefficient());
  }


  @Override
  public String str() {
    return "spearman";
  }

  /**
   * A task to do calculate Pearson's correlation coefficient.
   * The intermediate calculations required for standard deviant of both columns could be calculated by existing code,
   * however the point is to perform the calculations by going through the data only once.
   * <p>
   * Note: If one of the observation's columns used to calculate Pearson's corr. coef. contains a NaN value,
   * the whole line is skipped.
   *
   * @see {@link water.rapids.ast.prims.advmath.AstVariance}
   */
  private static class SpearmanCorrelationCoefficientTask extends MRTask<SpearmanCorrelationCoefficientTask> {
    // Arguments obtained externally
    private final double _xMean;
    private final double _yMean;

    private double spearmanCorrelationCoefficient;

    // Required to later finish calculation of standard deviation
    private double _xDiffSquared = 0;
    private double _yDiffSquared = 0;
    private double _xyAvgDiffMul = 0;
    // If at least one of the vectors contains NaN, such line is skipped
    private long _linesVisited;
    
    /**
     * @param xMean Mean value of the first 'x' vector, with NaNs skipped
     * @param yMean Mean value of the second 'y' vector, with NaNs skipped
     */
    private SpearmanCorrelationCoefficientTask(final double xMean, final double yMean) {
      this._xMean = xMean;
      this._yMean = yMean;
    }

    @Override
    public void map(Chunk[] chunks) {
      assert chunks.length == 2; // Amount of linear correlation only calculated between two vectors at once
      final Chunk xChunk = chunks[0];
      final Chunk yChunk = chunks[1];

      for (int row = 0; row < chunks[0].len(); row++) {
        final double x = xChunk.atd(row);
        final double y = yChunk.atd(row);
        if (Double.isNaN(x) || Double.isNaN(y)) {
          continue; // Skip NaN values
        }
        _linesVisited++;

        final double xDiffFromMean = x - _xMean;
        final double yDiffFromMean = y - _yMean;
        _xyAvgDiffMul += xDiffFromMean * yDiffFromMean;

        _xDiffSquared += Math.pow(xDiffFromMean, 2);
        _yDiffSquared += Math.pow(yDiffFromMean, 2);
      }
    }


    @Override
    public void reduce(final SpearmanCorrelationCoefficientTask mrt) {
      // The intermediate results are addable. The final calculations are done afterwards.
      this._xDiffSquared += mrt._xDiffSquared;
      this._yDiffSquared += mrt._yDiffSquared;
      this._linesVisited += mrt._linesVisited;
      this._xyAvgDiffMul += mrt._xyAvgDiffMul;
    }

    @Override
    protected void postGlobal() {
      // X Standard deviation
      final double xStdDev = Math.sqrt(1D / (_linesVisited - 1) * _xDiffSquared);

      // Y Standard deviation
      final double yStdDev = Math.sqrt(1D / (_linesVisited - 1) * _yDiffSquared);

      spearmanCorrelationCoefficient = (_xyAvgDiffMul)
              / ((_linesVisited - 1) * xStdDev * yStdDev);
    }

    public double getSpearmanCorrelationCoefficient() {
      return spearmanCorrelationCoefficient;
    }
  }
}
