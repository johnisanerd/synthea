package org.mitre.synthea.helpers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.math.ode.DerivativeException;
import org.cqframework.cql.cql2elm.CqlSemanticException;
import org.mitre.synthea.engine.PhysiologySimulator;
import org.mitre.synthea.world.agents.Person;
import org.mitre.synthea.world.concepts.VitalSign;
import org.simulator.math.odes.MultiTable;
import org.simulator.math.odes.MultiTable.Block.Column;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;


/**
 * A ValueGenerator for generation of values from a physiology simulation.
 */
public class PhysiologyValueGenerator extends ValueGenerator {
  public static final URL GENERATORS_RESOURCE = ClassLoader.getSystemClassLoader()
      .getResource("physiology/generators");
  private static ConcurrentMap<String,SimRunner> RUNNER_CACHE
      = new ConcurrentHashMap<String,SimRunner>();
  private static ConcurrentMap<String,PhysiologyGeneratorConfig> CONFIG_CACHE
      = new ConcurrentHashMap<String,PhysiologyGeneratorConfig>();
  private SimRunner simRunner;
  private PhysiologyGeneratorConfig config;
  private VitalSign vitalSign;
  
  /**
   * A generator of VitalSign values from a physiology simulation.
   * @param config physiology configuration file
   * @param person Person instance to generate VitalSigns for
   */
  public PhysiologyValueGenerator(PhysiologyGeneratorConfig config, VitalSign vitalSign,
      Person person) {
    super(person);
    this.config = config;
    this.vitalSign = vitalSign;
    String runnerId = person.attributes.get(Person.ID) + ":" + config.getModel();
    
    if (RUNNER_CACHE.containsKey(runnerId)) {
      simRunner = RUNNER_CACHE.get(runnerId);
      
      // If a different configuration was provided with the same model, throw an error
      if (!simRunner.getConfig().equals(config)) {
        throw new RuntimeException("ERROR: Conflicting configurations for physiology model \""
            + config.getModel() + "\"");
      }
    } else {
      simRunner = new SimRunner(config, person);
      RUNNER_CACHE.put(runnerId, simRunner);
    }
  }
  
  /**
   * Returns a List of all PhysiologyValueGenerators defined in the configuration directory.
   * @return List of PhysiologyValueGenerator
   */
  public static List<PhysiologyValueGenerator> loadAll(Person person) {
    return loadAll(person, "");
  }
  
  /**
   * Loads all PhysiologyValueGenerators defined in the given generator configuration subdirectory.
   * @param person Person to generate values for
   * @param subfolder generator sub directory to load configurations from
   * @return List of PhysiologyValueGenerator
   */
  public static List<PhysiologyValueGenerator> loadAll(Person person, String subfolder) {
    String generatorsResource = GENERATORS_RESOURCE.getPath();

    String[] configExt = {"yml"};
    
    // Get all of the configuration files in the generator configuration path and all
    // of its subdirectories
    File baseFolder = new File(generatorsResource, subfolder);
    Collection<File> physiologyConfigFiles = FileUtils.listFiles(baseFolder, configExt, true);
    
    List<PhysiologyValueGenerator> allGenerators = new ArrayList<PhysiologyValueGenerator>();
    
    // Set the ValueGenerator for each VitalSign output in each configuration
    for (File cfgFile : physiologyConfigFiles) {
      allGenerators.addAll(PhysiologyValueGenerator.fromConfig(cfgFile, person));
    }
  
    return allGenerators;
  }
  
  /**
   * Instantiates PhysiologyValueGenerators for each VitalSign output in the generator
   * configuration at the provided path.
   * 
   * @param configFile generator configuration file
   * @param person Person to generate VitalSigns for
   * @return List of PhysiologyValueGenerator instances
   */
  public static List<PhysiologyValueGenerator> fromConfig(File configFile, Person person) {
    return fromConfig(getConfig(configFile), person);
  }
  
  /**
   * Instantiates PhysiologyValueGenerators for each VitalSign output in the generator
   * configuration.
   * 
   * @param generatorConfig generator configuration object
   * @param person Person to generate VitalSigns for
   * @return List of PhysiologyValueGenerator instances
   */
  public static List<PhysiologyValueGenerator> fromConfig(
      PhysiologyGeneratorConfig generatorConfig, Person person) {
    List<PhysiologyValueGenerator> generators = new ArrayList<PhysiologyValueGenerator>();
    
    for (IoMapper mapper : generatorConfig.getOutputs()) {
      if (mapper.getType() == IoMapper.IoType.VITAL_SIGN) {
        generators.add(new PhysiologyValueGenerator(
            generatorConfig,
            VitalSign.fromString(mapper.getTo()),
            person));
      }
    }
    
    return generators;
  }
  
  /**
   * Retrieves the PhysiologyValueGenerator configuration from the given path.
   * @param configPath path to the generator configuration file
   * @return generator configuration object
   */
  public static PhysiologyGeneratorConfig getConfig(String configPath) {
    File configFile = new File(GENERATORS_RESOURCE.getPath(), configPath);
    return getConfig(configFile);
  }
  
  /**
   * Retrieves the PhysiologyValueGenerator configuration from the given file.
   * @param configFile generator configuration file
   * @return generator configuration object
   */
  public static PhysiologyGeneratorConfig getConfig(File configFile) {
    
    String relativePath;
    try {
      relativePath = GENERATORS_RESOURCE.toURI().relativize(configFile.toURI()).getPath();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    
    // key is the path to the config file
    String configKey = relativePath;
    
    // if this config has already been loaded, grab it from cache
    if (CONFIG_CACHE.containsKey(configKey)) {
      return CONFIG_CACHE.get(configKey);
    }
    
    System.out.println("Loading physiology generator \"" + relativePath + "\"");
    
    FileInputStream inputStream;

    try {
      inputStream = new FileInputStream(configFile);
    } catch (FileNotFoundException ex) {
      throw new RuntimeException("PhysiologyValueGenerator configuration not found: \""
          + configFile.getPath() + "\".");
    }
    
    // Add type descriptions so Yaml knows how to instantiate our Lists
    Constructor constructor = new Constructor(PhysiologyGeneratorConfig.class);
    TypeDescription configDescription = new TypeDescription(PhysiologyGeneratorConfig.class);
    configDescription.addPropertyParameters("inputs", IoMapper.class);
    configDescription.addPropertyParameters("outputs", IoMapper.class);
    constructor.addTypeDescription(configDescription);
    
    // Parse the PhysiologyConfig from the yaml file
    Yaml yaml = new Yaml(constructor);
    PhysiologyGeneratorConfig config = (PhysiologyGeneratorConfig) yaml.load(inputStream);
    
    // Validate the configuration
    config.validate();
    
    // Add the config to the cache in case there are other PhysiologyValueGenerators
    // that need it
    CONFIG_CACHE.put(configKey, config);
    
    return config;
  }
  
  /**
   * Returns the VitalSign this generator targets.
   * @return VitalSign target
   */
  public VitalSign getVitalSign() {
    return vitalSign;
  }

  @Override
  public String toString() {

    final StringBuilder sb = new StringBuilder("PhysiologyValueGenerator {");

    sb.append("model=").append(config.getModel());
    
    sb.append(", VitalSigns=[");
    for (IoMapper mapper : config.getOutputs()) {
      if (mapper.getType() == IoMapper.IoType.VITAL_SIGN) {
        sb.append(mapper.getTo()).append(",");
      }
    }
    
    sb.append("], Attributes=[");
    for (IoMapper mapper : config.getOutputs()) {
      if (mapper.getType() == IoMapper.IoType.ATTRIBUTE) {
        sb.append(mapper.getTo()).append(",");
      }
    }
    
    sb.append("]}");

    return sb.toString();
  }

  @Override
  public double getValue(long time) {
    simRunner.execute(time);
    return simRunner.getVitalSignValue(vitalSign);
  }
  
  /**
   * ValueGenerator configuration for a physiology model file.
   * @author RSIVEK
   *
   */
  public static class PhysiologyGeneratorConfig {
    private String model;
    private String solver;
    private double stepSize;
    private double simDuration;
    private double leadTime;
    private List<IoMapper> inputs;
    private List<IoMapper> outputs;
    
    /**
     * Validates that all inputs are appropriate and within bounds.
     */
    protected void validate() {
      if (leadTime >= simDuration) {
        throw new IllegalArgumentException(
            "Simulation lead time must be less than simulation duration!");
      }
      
      for (IoMapper mapper : outputs) {
        // Will throw an IllegalArgumentException if the provided VitalSign is invalid
        if (mapper.getType() == IoMapper.IoType.VITAL_SIGN) {
          VitalSign.fromString(mapper.getTo());
        }
      }
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getSolver() {
      return solver;
    }

    public void setSolver(String solver) {
      this.solver = solver;
    }

    public double getStepSize() {
      return stepSize;
    }

    public void setStepSize(double stepSize) {
      this.stepSize = stepSize;
    }

    public double getSimDuration() {
      return simDuration;
    }

    public void setSimDuration(double simDuration) {
      this.simDuration = simDuration;
    }

    public double getLeadTime() {
      return leadTime;
    }

    public void setLeadTime(double leadTime) {
      this.leadTime = leadTime;
    }

    public List<IoMapper> getInputs() {
      return inputs;
    }

    public void setInputs(List<IoMapper> inputs) {
      this.inputs = inputs;
    }

    public List<IoMapper> getOutputs() {
      return outputs;
    }

    public void setOutputs(List<IoMapper> outputs) {
      this.outputs = outputs;
    }
  }
  
  /** Class for handling simulation inputs and outputs. **/
  public static class IoMapper {
    private IoType type;
    private String from;
    private String to;
    private String fromList;
    private String fromExp;
    private double varianceThreshold;
    private ExpressionProcessor expProcessor;
    
    enum IoType {
      ATTRIBUTE, 
      VITAL_SIGN
    }
    
    public IoType getType() {
      return type;
    }

    public void setType(IoType type) {
      this.type = type;
    }

    public String getFrom() {
      return from;
    }

    public void setFrom(String from) {
      this.from = from;
    }

    public String getTo() {
      return to;
    }

    public void setTo(String to) {
      this.to = to;
    }

    public String getFromList() {
      return fromList;
    }

    public void setFromList(String fromList) {
      this.fromList = fromList;
    }

    public String getFromExp() {
      return fromExp;
    }

    public void setFromExp(String fromExp) {
      this.fromExp = fromExp;
    }

    public double getVarianceThreshold() {
      return varianceThreshold;
    }

    public void setVarianceThreshold(double varianceThreshold) {
      this.varianceThreshold = varianceThreshold;
    }

    public ExpressionProcessor getExpProcessor() {
      return expProcessor;
    }

    public void setExpProcessor(ExpressionProcessor expProcessor) {
      this.expProcessor = expProcessor;
    }

    /**
     * Initializes the expression processor if needed.
     * @param paramTypes map of parameters to their CQL types
     */
    public void initialize(Map<String, String> paramTypes) {
      try {
        if (expProcessor == null && fromExp != null && !"".equals(fromExp)) {
          expProcessor = new ExpressionProcessor(fromExp, paramTypes);
        }
      } catch (CqlSemanticException e) {
        throw new RuntimeException(e);
      }
    }
    
    /**
     * Populates model input parameters from the given person object.
     * @param person Person instance to get parameter values from
     * @param time Synthea simulation time
     * @param modelInputs map of input parameters to be populated
     */
    public double toModelInputs(Person person, long time, Map<String,Double> modelInputs) {
      double resultValue;
      
      // Evaluate the expression if one is provided
      if (expProcessor != null) {
        Map<String,Object> expParams = new HashMap<String,Object>();
        
        // Add all patient parameters to the expression parameter map
        for (String param : expProcessor.getParamNames()) {
          expParams.put(param, new BigDecimal(getPersonValue(param, person, time)));
        }
        
        // All physiology inputs should evaluate to numeric parameters
        BigDecimal result = expProcessor.evaluateNumeric(expParams);
        resultValue = result.doubleValue();
      } else if (fromList != null) {
        throw new IllegalArgumentException(
            "Cannot map lists from person attributes / vital signs to model parameters");
      } else {
        resultValue = getPersonValue(from, person, time);
      }
      
      modelInputs.put(to, resultValue);
      return resultValue;
    }
    
    /**
     * Evaluates the provided expression given the simulation results.
     * @param results simulation results
     * @param leadTime lead time in seconds before using simulation values
     * @return BigDecimal result value
     */
    private BigDecimal getExpressionResult(MultiTable results, double leadTime) {
      if (expProcessor == null) {
        throw new RuntimeException("No expression to process");
      }
      
      // Create our map of expression parameters
      Map<String,Object> expParams = new HashMap<String,Object>();
      
      // Get the index past the lead time to start getting values
      int leadTimeIdx = Arrays.binarySearch(results.getTimePoints(), leadTime);
      
      // Add all model outputs to the expression parameter map as lists of decimals
      for (String param : expProcessor.getParamNames()) {
        List<BigDecimal> paramList = new ArrayList<BigDecimal>(results.getRowCount());
        
        Column col = results.getColumn(param);
        if (col == null) {
          throw new IllegalArgumentException("Invalid model parameter \"" + param
              + "\" in expression \"" + from
              + "\" cannot be mapped to patient attribute \"" + to + "\"");
        }
        
        for (int i = leadTimeIdx; i < col.getRowCount(); i++) {
          paramList.add(new BigDecimal(col.getValue(i)));
        }
        expParams.put(param, paramList);
      }
      
      // Evaluate the expression
      return expProcessor.evaluateNumeric(expParams);
    }
    
    /**
     * Retrieves the numeric result for this IoMapper from simulation output.
     * @param results simulation results
     * @param leadTime lead time in seconds before using simulation values
     * @return double value or List of Double values
     */
    public Object getOutputResult(MultiTable results, double leadTime) {
      
      if (expProcessor != null) {
        // Evaluate the expression and return the result
        return getExpressionResult(results, leadTime).doubleValue();
        
      } else if (fromList != null) {
        // Get the column for the requested list
        Column col = results.getColumn(fromList);
        if (col == null) {
          throw new IllegalArgumentException("Invalid model parameter \"" + fromList
              + "\" cannot be mapped to patient attribute \"" + to + "\"");
        }
        
        // Make it an ArrayList for more natural usage throughout the rest of the application
        List<Double> valueList = new ArrayList<Double>();
        col.iterator().forEachRemaining(valueList::add);
        
        // Return the list
        return valueList;
      } else {
        // Result is the last value of the requested parameter
        int lastRow = results.getRowCount() - 1;
        Column col = results.getColumn(from);
        if (col == null) {
          throw new IllegalArgumentException("Invalid model parameter \"" + from
              + "\" cannot be mapped to VitalSign \"" + to + "\"");
        }
        return col.getValue(lastRow);
      }
    }
    
    /** 
     * Retrieve the desired value from a Person model. Check for a VitalSign first and
     * then an attribute if there is no VitalSign by the provided name.
     * Throws an IllegalArgumentException if neither exists.
     * @param param name of the VitalSign or attribute to retrieve from the Person
     * @param person Person instance to get the parameter from
     * @param time current time
     * @return value
     */
    private Double getPersonValue(String param, Person person, long time) {
      
      // Treat "age" as a special case. In expressions, age is represented in decimal years
      if (param.equals("age")) {
        return person.ageInDecimalYears(time);
      }
      
      org.mitre.synthea.world.concepts.VitalSign vs = null;
      try {
        vs = org.mitre.synthea.world.concepts.VitalSign.fromString(param);
      } catch (IllegalArgumentException ex) {
        // Ignore since it actually may not be a vital sign
      }

      if (vs != null) {
        return person.getVitalSign(vs, time);
      } else if (person.attributes.containsKey(param)) {
        Object value = person.attributes.get(param);
        
        if (value instanceof Number) {
          return ((Number) value).doubleValue();
          
        } else if (value instanceof Boolean) {
          return (Boolean) value ? 1.0 : 0.0;
          
        } else {
          if (expProcessor != null) {
            throw new IllegalArgumentException("Unable to map person attribute \""
                + param + "\" in expression \"" + fromExp + "\" for parameter \""
                + to + "\": Attribute value is not a number.");
          } else {
            throw new IllegalArgumentException("Unable to map person attribute \""
                + param + "\" to parameter \"" + to + "\": Attribute value is not a number.");
          }
        }
      } else {
        if (expProcessor != null) {
          throw new IllegalArgumentException("Unable to map \"" + param
              + "\" in expression \"" + fromExp + "\" for parameter \"" + to
              + "\": Invalid person attribute or vital sign.");
        } else {
          throw new IllegalArgumentException("Unable to map \""
              + param + "\" to parameter \"" + to
              + "\": Invalid person attribute or vital sign.");
        }
      }
    }
  }
  
  /** Class for handling execution of a PhysiologySimulator. **/
  private static class SimRunner {
    private PhysiologyGeneratorConfig config;
    private Person person;
    private PhysiologySimulator simulator;
    private Map<String,String> paramTypes = new HashMap<String, String>();
    private Map<String,Double> prevInputs = new HashMap<String, Double>();
    private Map<VitalSign,Double> vitalSignResults = new HashMap<VitalSign,Double>();
    
    /**
     * Handles execution of a PhysiologySimulator.
     * @param config simulation configuration
     */
    public SimRunner(PhysiologyGeneratorConfig config, Person person) {
      this.config = config;
      this.person = person;
      simulator = new PhysiologySimulator(
          config.getModel(),
          config.getSolver(),
          config.getStepSize(),
          config.getSimDuration()
      );
      
      for (String param : simulator.getParameters()) {
        // Assume all physiology model parameters are numeric
        // TODO: May need to handle alternative types in the future
        paramTypes.put(param, "List<Decimal>");
      }
      
      for (IoMapper mapper : config.getInputs()) {
        mapper.initialize(paramTypes);
      }
      for (IoMapper mapper : config.getOutputs()) {
        mapper.initialize(paramTypes);
      }
    }
    
    /**
     * Retrieves the simulation configuration.
     * @return simulation configuration
     */
    public PhysiologyGeneratorConfig getConfig() {
      return config;
    }
    
    public double getVitalSignValue(VitalSign parameter) {
      return vitalSignResults.get(parameter);
    }
    
    /**
     * Executes the simulation if any input values are beyond the variance threshold.
     * @param time simulation time
     */
    public void execute(long time) {
      // Flag to indicate if the input values have sufficiently changed
      boolean sufficientChange = false;
      
      // Get our map of inputs
      Map<String,Double> modelInputs = new HashMap<String,Double>();
      for (IoMapper mapper : config.getInputs()) {
        double inputResult = mapper.toModelInputs(person, time, modelInputs);
        
        // If we have previous results, check if there has been a sufficient
        // change in the input parameter
        if (!prevInputs.isEmpty() && Math.abs(inputResult
            - prevInputs.get(mapper.getTo()))
            > mapper.getVarianceThreshold()) {
          sufficientChange = true;
        }
      }
      
      // If the simulation has never been run, or there's sufficient change
      // in the input parameters, run the simulation
      if (vitalSignResults.isEmpty() || sufficientChange) {
        // Save our input parameters for future threshold checks
        prevInputs = modelInputs;
        MultiTable results = runSim(time, modelInputs);
        
//        System.out.println("Running simulation");
        
        // Set all of the results
        for (IoMapper mapper : config.getOutputs()) {
          switch (mapper.getType()) {
            default:
            case ATTRIBUTE:
              person.attributes.put(mapper.getTo(),
                  mapper.getOutputResult(results, config.getLeadTime()));
              break;
            case VITAL_SIGN:
              VitalSign vs = VitalSign.fromString(mapper.getTo());
              Object result = mapper.getOutputResult(results, config.getLeadTime());
              if (result instanceof List) {
                throw new IllegalArgumentException(
                    "Mapping lists to VitalSigns is currently unsupported. "
                    + "Cannot map list to VitalSign \"" + mapper.getTo() + "\".");
              }
              vitalSignResults.put(vs, (double) result);
//              System.out.println(vitalSignResults);
              break;
          }
        }
      }
      
    }
    
    /**
     * Runs the simulation and returns the results.
     * @param time simulation time
     * @return simulation results
     */
    private MultiTable runSim(long time, Map<String,Double> modelInputs) {
      try {
        MultiTable results = simulator.run(modelInputs);
        return results;
      } catch (DerivativeException ex) {
        Logger.getLogger(this.getClass().getName()).log(
            Level.SEVERE, "Unable to solve simulation \""
            + config.model + "\" at time step " + time + " for person "
            + person.attributes.get(Person.ID), ex);
      }
      return null;
    }
  }
}