/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.parquet.avro;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.io.api.RecordMaterializer;
import org.apache.parquet.schema.MessageType;

/**
 * Avro implementation of {@link ReadSupport} for avro generic, specific, and
 * reflect models. Use {@link AvroParquetReader} or
 * {@link AvroParquetInputFormat} rather than using this class directly.
 */
public class AvroReadSupport<T> extends ReadSupport<T> {

  public static String AVRO_REQUESTED_PROJECTION = "parquet.avro.projection";
  private static final String AVRO_READ_SCHEMA = "parquet.avro.read.schema";

  static final String AVRO_SCHEMA_METADATA_KEY = "parquet.avro.schema";
  // older files were written with the schema in this metadata key
  static final String OLD_AVRO_SCHEMA_METADATA_KEY = "avro.schema";
  private static final String AVRO_READ_SCHEMA_METADATA_KEY = "avro.read.schema";

  // TODO: for 2.0.0, make this final (breaking change)
  public static String AVRO_DATA_SUPPLIER = "parquet.avro.data.supplier";

  public static final String AVRO_COMPATIBILITY = "parquet.avro.compatible";
  public static final boolean AVRO_DEFAULT_COMPATIBILITY = true;

  /**
   * @see org.apache.parquet.avro.AvroParquetInputFormat#setRequestedProjection(org.apache.hadoop.mapreduce.Job, org.apache.avro.Schema)
   */
  public static void setRequestedProjection(Configuration configuration, Schema requestedProjection) {
    configuration.set(AVRO_REQUESTED_PROJECTION, requestedProjection.toString());
  }

  /**
   * @see org.apache.parquet.avro.AvroParquetInputFormat#setAvroReadSchema(org.apache.hadoop.mapreduce.Job, org.apache.avro.Schema)
   */
  public static void setAvroReadSchema(Configuration configuration, Schema avroReadSchema) {
    configuration.set(AVRO_READ_SCHEMA, avroReadSchema.toString());
  }

  public static void setAvroDataSupplier(Configuration configuration,
      Class<? extends AvroDataSupplier> clazz) {
    configuration.set(AVRO_DATA_SUPPLIER, clazz.getName());
  }

  private GenericData model = null;

  public AvroReadSupport() {
  }

  public AvroReadSupport(GenericData model) {
    this.model = model;
  }

  @Override
  public ReadContext init(Configuration configuration,
                          Map<String, String> keyValueMetaData,
                          MessageType fileSchema) {
    MessageType projection = fileSchema;
    Map<String, String> metadata = new LinkedHashMap<String, String>();

    String requestedProjectionString = configuration.get(AVRO_REQUESTED_PROJECTION);
    if (requestedProjectionString != null) {
      Schema avroRequestedProjection = new Schema.Parser().parse(requestedProjectionString);
      projection = new AvroSchemaConverter(configuration).convert(avroRequestedProjection);
    }

    String avroReadSchema = configuration.get(AVRO_READ_SCHEMA);
    if (avroReadSchema != null) {
      metadata.put(AVRO_READ_SCHEMA_METADATA_KEY, avroReadSchema);
    }

    if (configuration.getBoolean(AVRO_COMPATIBILITY, AVRO_DEFAULT_COMPATIBILITY)) {
      metadata.put(AVRO_COMPATIBILITY, "true");
    }

    return new ReadContext(projection, metadata);
  }

  @Override
  public RecordMaterializer<T> prepareForRead(
      Configuration configuration, Map<String, String> keyValueMetaData,
      MessageType fileSchema, ReadContext readContext) {
    Map<String, String> metadata = readContext.getReadSupportMetadata();
    MessageType parquetSchema = readContext.getRequestedSchema();
    Schema avroSchema;

    boolean validateDefaults = configuration.getBoolean("parquet.avro.validate.defaults", true);
    if (metadata.get(AVRO_READ_SCHEMA_METADATA_KEY) != null) {
      // use the Avro read schema provided by the user
      avroSchema = new Schema.Parser().setValidateDefaults(validateDefaults).parse(metadata.get(AVRO_READ_SCHEMA_METADATA_KEY));
    } else if (keyValueMetaData.get(AVRO_SCHEMA_METADATA_KEY) != null) {
      // use the Avro schema from the file metadata if present
      avroSchema = new Schema.Parser().setValidateDefaults(validateDefaults).parse(keyValueMetaData.get(AVRO_SCHEMA_METADATA_KEY));
    } else if (keyValueMetaData.get(OLD_AVRO_SCHEMA_METADATA_KEY) != null) {
      // use the Avro schema from the file metadata if present
      avroSchema = new Schema.Parser().parse(keyValueMetaData.get(OLD_AVRO_SCHEMA_METADATA_KEY));
    } else {
      // default to converting the Parquet schema into an Avro schema
      avroSchema = new AvroSchemaConverter(configuration).convert(parquetSchema);
    }

    GenericData model = getDataModel(configuration);
    String compatEnabled = metadata.get(AvroReadSupport.AVRO_COMPATIBILITY);
    if (compatEnabled != null && Boolean.valueOf(compatEnabled)) {
      return newCompatMaterializer(parquetSchema, avroSchema, model);
    }
    return new AvroRecordMaterializer<T>(parquetSchema, avroSchema, model);
  }

  @SuppressWarnings("unchecked")
  private static <T> RecordMaterializer<T> newCompatMaterializer(
      MessageType parquetSchema, Schema avroSchema, GenericData model) {
    return (RecordMaterializer<T>) new AvroCompatRecordMaterializer(
        parquetSchema, avroSchema, model);
  }

  private GenericData getDataModel(Configuration conf) {
    if (model != null) {
      return model;
    }
    Class<? extends AvroDataSupplier> suppClass = conf.getClass(
        AVRO_DATA_SUPPLIER, SpecificDataSupplier.class, AvroDataSupplier.class);
    return ReflectionUtils.newInstance(suppClass, conf).get();
  }
}
