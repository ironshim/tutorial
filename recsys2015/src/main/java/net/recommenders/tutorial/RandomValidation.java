package net.recommenders.tutorial;

import net.recommenders.rival.core.DataModel;
import net.recommenders.rival.core.DataModelUtils;
import net.recommenders.rival.core.SimpleParser;
import net.recommenders.rival.core.UIPParser;
import net.recommenders.rival.evaluation.metric.error.RMSE;
import net.recommenders.rival.evaluation.metric.ranking.NDCG;
import net.recommenders.rival.evaluation.metric.ranking.Precision;
import net.recommenders.rival.evaluation.strategy.EvaluationStrategy;
import net.recommenders.rival.examples.DataDownloader;
import net.recommenders.rival.recommend.frameworks.RecommenderIO;
import net.recommenders.rival.recommend.frameworks.exceptions.RecommenderException;
import net.recommenders.rival.recommend.frameworks.mahout.GenericRecommenderBuilder;
import net.recommenders.rival.split.splitter.RandomSplitter;
import org.apache.commons.cli.*;
import org.apache.mahout.cf.taste.common.TasteException;
import org.apache.mahout.cf.taste.impl.common.LongPrimitiveIterator;
import org.apache.mahout.cf.taste.impl.model.file.FileDataModel;
import org.apache.mahout.cf.taste.recommender.RecommendedItem;
import org.apache.mahout.cf.taste.recommender.Recommender;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * Example where data from MovieTweetings is used to evaluate an IB recommender.
 *
 * @author <a href="http://github.com/alansaid">Alan</a>.
 */
public final class RandomValidation {

  /**
   * Default percentage.
   */
  public static float PERCENTAGE = 0.2f;
  /**
   * Default neighbohood size.
   */
  public static int NEIGH_SIZE = 50;
  /**
   * Default cutoff for evaluation metrics.
   */
  public static int AT = 10;
  /**
   * Default relevance threshold.
   */
  public static double REL_TH = 3.0;
  /**
   * Default seed.
   */
  public static final long SEED = 2048L;
  /**
   * Token position for user information in MovieTweetings.
   */
  private static final int USER_TOK = 0;
  /**
   * Token position for item information in MovieTweetings.
   */
  private static final int ITEM_TOK = 2;
  /**
   * Token position for preference information in MovieTweetings.
   */
  private static final int PREF_TOK = 4;
  /**
   * Token position for time information in MovieTweetings.
   */
  private static final int TIME_TOK = 6;

  /**
   * Utility classes should not have a public or default constructor.
   */
  private RandomValidation() {
  }

  /**
   * Main method. Parameter is not used.
   *
   * @param args the arguments (not used)
   */
  public static void main(final String[] args) throws FileNotFoundException, UnsupportedEncodingException, InvocationTargetException, NoSuchMethodException, ClassNotFoundException, InstantiationException, IllegalAccessException {
    String url = "https://raw.githubusercontent.com/sidooms/MovieTweetings/master/snapshots/10K/ratings.dat";
    String modelPath = "data/movietweeting/model/";
    String recPath = "data/movietweeting/recommendations/";
    String folder = "data/movietweeting";
    ;
    prepareSplits(url, PERCENTAGE, "data/movietweeting/ratings.dat", folder, modelPath);
    recommend(modelPath, recPath);
    prepareStrategy(modelPath, recPath, modelPath);
    evaluate(modelPath, recPath);
  }

  private static void parseCLI(String[] args) throws ParseException {
    Options options = new Options();
    options.addOption("t", true, "threshold");
    options.addOption("p", true, "percentage");
    options.addOption("a", true, "at");
    CommandLineParser parser = new DefaultParser();
    CommandLine cmd = parser.parse(options, args);
    REL_TH = (null != cmd.getOptionValue("t") ? Double.parseDouble(cmd.getOptionValue("t")) : REL_TH);
    PERCENTAGE = (null != cmd.getOptionValue("") ? Float.parseFloat(cmd.getOptionValue("u")) : PERCENTAGE);
    AT = (null != cmd.getOptionValue("") ? Integer.parseInt(cmd.getOptionValue("u")) : AT);
  }

  /**
   * Downloads a dataset and stores the splits generated from it.
   *
   * @param url url where dataset can be downloaded from
   * @param percentage percentage to be used in the random split
   * @param inFile file to be used once the dataset has been downloaded
   * @param folder folder where dataset will be stored
   * @param outPath path where the splits will be stored
   */
  public static void prepareSplits(final String url, final float percentage, final String inFile, final String folder, final String outPath) throws FileNotFoundException, UnsupportedEncodingException {
    DataDownloader dd = new DataDownloader(url, folder);
    dd.download();

    boolean perUser = true;
    long seed = SEED;
    UIPParser parser = new UIPParser();

    parser.setDelimiter(':');
    parser.setUserTok(USER_TOK);
    parser.setItemTok(ITEM_TOK);
    parser.setPrefTok(PREF_TOK);
    parser.setTimeTok(TIME_TOK);

    DataModel<Long, Long> data = null;
    try {
      data = parser.parseData(new File(inFile));
    } catch (IOException e) {
      e.printStackTrace();
    }

    DataModel<Long, Long>[] splits = new RandomSplitter(percentage, perUser, seed, false).split(data);
    File dir = new File(outPath);
    if (!dir.exists()) {
      if (!dir.mkdir()) {
        System.err.println("Directory " + dir + " could not be created");
        return;
      }
    }
    for (int i = 0; i < splits.length / 2; i++) {
      DataModel<Long, Long> training = splits[2 * i];
      DataModel<Long, Long> test = splits[2 * i + 1];
      String trainingFile = outPath + "train_" + i + ".csv";
      String testFile = outPath + "test_" + i + ".csv";
      System.out.println("train: " + trainingFile);
      System.out.println("test: " + testFile);
      boolean overwrite = true;
      DataModelUtils.saveDataModel(training, trainingFile, overwrite);
      DataModelUtils.saveDataModel(test, testFile, overwrite);
    }
  }

  /**
   * Recommends using an IB algorithm.
   *
   * @param inPath path where training and test models have been stored
   * @param outPath path where recommendation files will be stored
   */
  public static void recommend(final String inPath, final String outPath) {
    int i = 0;
    org.apache.mahout.cf.taste.model.DataModel trainModel;
    org.apache.mahout.cf.taste.model.DataModel testModel;
    try {
      trainModel = new FileDataModel(new File(inPath + "train_" + i + ".csv"));
      testModel = new FileDataModel(new File(inPath + "test_" + i + ".csv"));
    } catch (IOException e) {
      e.printStackTrace();
      return;
    }

    GenericRecommenderBuilder grb = new GenericRecommenderBuilder();
    String recommenderClass = "org.apache.mahout.cf.taste.impl.recommender.GenericItemBasedRecommender";
    String similarityClass = "org.apache.mahout.cf.taste.impl.similarity.TanimotoCoefficientSimilarity";
    Recommender recommender = null;
    try {
      recommender = grb.buildRecommender(trainModel, recommenderClass, similarityClass);
    } catch (RecommenderException e) {
      e.printStackTrace();
    }

    String fileName = "recs_" + i + ".csv";

    LongPrimitiveIterator users = null;
    try {
      users = testModel.getUserIDs();
      boolean createFile = true;
      while (users.hasNext()) {
        long u = users.nextLong();
        assert recommender != null;
        List<RecommendedItem> items = recommender.recommend(u, trainModel.getNumItems());
        RecommenderIO.writeData(u, items, outPath, fileName, !createFile, null);
        createFile = false;
      }
    } catch (TasteException e) {
      e.printStackTrace();
    }
  }

  /**
   * Prepares the strategies to be evaluated with the recommenders already
   * generated.
   *
   * @param splitPath path where splits have been stored
   * @param recPath path where recommendation files have been stored
   * @param outPath path where the filtered recommendations will be stored
   */
  @SuppressWarnings("unchecked")
  public static void prepareStrategy(final String splitPath, final String recPath, final String outPath) throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, ClassNotFoundException, FileNotFoundException, UnsupportedEncodingException {
    int i = 0;
    File trainingFile = new File(splitPath + "train_" + i + ".csv");
    File testFile = new File(splitPath + "test_" + i + ".csv");
    File recFile = new File(recPath + "recs_" + i + ".csv");
    DataModel<Long, Long> trainingModel;
    DataModel<Long, Long> testModel;
    DataModel<Long, Long> recModel;
    try {
      trainingModel = new SimpleParser().parseData(trainingFile);
      testModel = new SimpleParser().parseData(testFile);
      recModel = new SimpleParser().parseData(recFile);
    } catch (IOException e) {
      e.printStackTrace();
      return;
    }

    Double threshold = REL_TH;
    String strategyClassName = "net.recommenders.rival.evaluation.strategy.UserTest";
    EvaluationStrategy<Long, Long> strategy = null;
    strategy = (EvaluationStrategy<Long, Long>) (Class.forName(strategyClassName)).getConstructor(DataModel.class, DataModel.class, double.class).
        newInstance(trainingModel, testModel, threshold);

    DataModel<Long, Long> modelToEval = new DataModel<Long, Long>();

    for (Long user : recModel.getUsers()) {
      assert strategy != null;
      for (Long item : strategy.getCandidateItemsToRank(user)) {
        if (recModel.getUserItemPreferences().get(user).containsKey(item)) {
          modelToEval.addPreference(user, item, recModel.getUserItemPreferences().get(user).get(item));
        }
      }
    }
    DataModelUtils.saveDataModel(modelToEval, outPath + "strategymodel_" + i + ".csv", true);
  }

  /**
   * Evaluates the recommendations generated in previous steps.
   *
   * @param splitPath path where splits have been stored
   * @param recPath path where recommendation files have been stored
   */
  public static void evaluate(final String splitPath, final String recPath) {
    double ndcgRes = 0.0;
    double precisionRes = 0.0;
    double rmseRes = 0.0;
    int i = 0;
    File testFile = new File(splitPath + "test_" + i + ".csv");
    File recFile = new File(recPath + "recs_" + i + ".csv");
    DataModel<Long, Long> testModel = null;
    DataModel<Long, Long> recModel = null;
    try {
      testModel = new SimpleParser().parseData(testFile);
      recModel = new SimpleParser().parseData(recFile);
    } catch (IOException e) {
      e.printStackTrace();
    }
    NDCG<Long, Long> ndcg = new NDCG<Long, Long>(recModel, testModel, new int[]{AT});
    ndcg.compute();
    ndcgRes += ndcg.getValueAt(AT);

    RMSE<Long, Long> rmse = new RMSE<Long, Long>(recModel, testModel);
    rmse.compute();
    rmseRes += rmse.getValue();

    Precision<Long, Long> precision = new Precision<Long, Long>(recModel, testModel, REL_TH, new int[]{AT});
    precision.compute();
    precisionRes += precision.getValueAt(AT);

    System.out.println("NDCG@" + AT + ": " + ndcgRes);
    System.out.println("RMSE: " + rmseRes);
    System.out.println("P@" + AT + ": " + precisionRes);
  }
}
