/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.metron.common.stellar.shell;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Iterables;
import org.apache.commons.collections4.trie.PatriciaTrie;
import org.apache.commons.lang.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.metron.common.configuration.ConfigurationsUtils;
import org.apache.metron.common.dsl.*;
import org.apache.metron.common.stellar.StellarProcessor;
import org.apache.metron.common.utils.JSONUtils;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.apache.metron.common.configuration.ConfigurationsUtils.readGlobalConfigBytesFromZookeeper;
import static org.apache.metron.common.stellar.shell.StellarExecutor.OperationType.DOC;
import static org.apache.metron.common.stellar.shell.StellarExecutor.OperationType.NORMAL;

/**
 * Executes Stellar expressions and maintains state across multiple invocations.
 */
public class StellarExecutor {

  private ReadWriteLock indexLock = new ReentrantReadWriteLock();

  /**
   * prefix tree index of autocompletes
   */
  private PatriciaTrie<AutoCompleteType> autocompleteIndex;
  /**
   * The variables known by Stellar.
   */
  private Map<String, Object> variables;

  /**
   * The function resolver.
   */
  private FunctionResolver functionResolver ;

  /**
   * A Zookeeper client. Only defined if given a valid Zookeeper URL.
   */
  private Optional<CuratorFramework> client;

  /**
   * The Stellar execution context.
   */
  private Context context;

  public enum OperationType {
    DOC,MAGIC,NORMAL;
  }

  public interface AutoCompleteTransformation {
    String transform(OperationType type, String key);
  }

  public enum AutoCompleteType implements AutoCompleteTransformation{
      FUNCTION((type, key) -> {
        if(type == DOC) {
          return StellarShell.DOC_PREFIX + key;
        }
        else if(type == NORMAL) {
          return key + "(";
        }
        return key;
      })
    , VARIABLE((type, key) -> key )
    , TOKEN((type, key) -> key)
    ;
    AutoCompleteTransformation transform;
    AutoCompleteType(AutoCompleteTransformation transform) {
      this.transform = transform;
    }

    @Override
    public String transform(OperationType type, String key) {
      return transform.transform(type, key);
    }

  }

  public StellarExecutor() throws Exception {
    this(null);
  }

  public StellarExecutor(String zookeeperUrl) throws Exception {
    this.variables = new HashMap<>();
    this.functionResolver = new StellarFunctions().FUNCTION_RESOLVER();
    this.client = createClient(zookeeperUrl);
    this.context = createContext();
    this.autocompleteIndex = initializeIndex();
    //Asynchronously update the index with function names found from a classpath scan.
    new Thread( () -> {
        Iterable<StellarFunctionInfo> functions = functionResolver.getFunctionInfo();
        indexLock.writeLock().lock();
        try {
          for(StellarFunctionInfo info: functions) {
            String functionName = info.getName();
            autocompleteIndex.put(functionName, AutoCompleteType.FUNCTION);
          }
        }
          finally {
            System.out.println("Functions loaded, you may refer to functions now...");
            indexLock.writeLock().unlock();
          }
    }).start();
  }

  private PatriciaTrie<AutoCompleteType> initializeIndex() {
    Map<String, AutoCompleteType> index = new HashMap<>();

    index.put("==", AutoCompleteType.TOKEN);
    index.put(">=", AutoCompleteType.TOKEN);
    index.put("<=", AutoCompleteType.TOKEN);
    index.put(":=", AutoCompleteType.TOKEN);
    index.put("quit", AutoCompleteType.TOKEN);
    index.put(StellarShell.MAGIC_FUNCTIONS, AutoCompleteType.FUNCTION);
    index.put(StellarShell.MAGIC_VARS, AutoCompleteType.FUNCTION);
    return new PatriciaTrie<>(index);
  }

  public Iterable<String> autoComplete(String buffer, final OperationType opType) {
    indexLock.readLock().lock();
    try {
      SortedMap<String, AutoCompleteType> ret = autocompleteIndex.prefixMap(buffer);
      if (ret.isEmpty()) {
        return new ArrayList<>();
      }
      return Iterables.transform(ret.entrySet(), kv -> kv.getValue().transform(opType, kv.getKey()));
    }
    finally {
      indexLock.readLock().unlock();
    }
  }

  /**
   * Creates a Zookeeper client.
   * @param zookeeperUrl The Zookeeper URL.
   */
  private Optional<CuratorFramework> createClient(String zookeeperUrl) {

    // can only create client, if have valid zookeeper URL
    if(StringUtils.isNotBlank(zookeeperUrl)) {
      CuratorFramework client = ConfigurationsUtils.getClient(zookeeperUrl);
      client.start();
      return Optional.of(client);

    } else {
      return Optional.empty();
    }
  }

  /**
   * Creates a Context initialized with configuration stored in Zookeeper.
   */
  private Context createContext() throws Exception {
    Context context = Context.EMPTY_CONTEXT();

    // load global configuration from zookeeper
    if (client.isPresent()) {

      // fetch the global configuration
      Map<String, Object> global = JSONUtils.INSTANCE.load(
              new ByteArrayInputStream(readGlobalConfigBytesFromZookeeper(client.get())),
              new TypeReference<Map<String, Object>>() {
              });

      context = new Context.Builder()
              .with(Context.Capabilities.GLOBAL_CONFIG, () -> global)
              .with(Context.Capabilities.ZOOKEEPER_CLIENT, () -> client.get())
              .build();
    }

    return context;
  }

  /**
   * Executes the Stellar expression and returns the result.
   * @param expression The Stellar expression to execute.
   * @return The result of the expression.
   */
  public Object execute(String expression) {
    VariableResolver variableResolver = new MapVariableResolver(variables, Collections.emptyMap());
    StellarProcessor processor = new StellarProcessor();
    return processor.parse(expression, variableResolver, functionResolver, context);
  }

  /**
   * Assigns a value to a variable.
   * @param variable The name of the variable.
   * @param value The value of the variable
   */
  public void assign(String variable, Object value) {
    this.variables.put(variable, value);
    indexLock.writeLock().lock();
    try {
      if (value != null) {
        this.autocompleteIndex.put(variable, AutoCompleteType.VARIABLE);
      } else {
        this.autocompleteIndex.remove(variable);
      }
    }
    finally {
      indexLock.writeLock().unlock();
    }
  }

  public Map<String, Object> getVariables() {
    return this.variables;
  }

  public FunctionResolver getFunctionResolver() {
    return functionResolver;
  }

  public Context getContext() {
    return context;
  }
}
