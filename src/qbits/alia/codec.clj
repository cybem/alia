(ns qbits.alia.codec
  (:import
   (java.nio ByteBuffer)
   (com.datastax.driver.core
    DataType
    DataType$Name
    GettableByIndexData
    ResultSet
    Row
    UserType$Field)))

(defmacro make-decoders [x idx col-type & specs]
  (reduce (fn [m [decoder-type# form#]]
            (assoc m
              decoder-type#
              `(fn [~(vary-meta x assoc :tag "com.datastax.driver.core.GettableByIndexData")
                    ~(vary-meta idx assoc :tag "java.lang.Integer")
                    ~(vary-meta col-type assoc :tag "com.datastax.driver.core.DataType")]
                 ~form#)))
          {}
          (partition 2 specs)))

(defn ^Class types-args->type
  [^"[Lcom.datastax.driver.core.DataType;" type-args pred]
  (.asJavaClass ^DataType (pred type-args)))

(declare decoders)
(defn decode
  [^GettableByIndexData x ^Integer idx ^DataType col-type]
  ((decoders (.getName col-type)) x idx col-type))

(def decoders
  (make-decoders x idx col-type
   DataType$Name/ASCII     (.getString x idx)
   DataType$Name/BIGINT    (.getLong x idx)
   DataType$Name/BLOB      (.getBytes x idx)
   DataType$Name/BOOLEAN   (.getBool x idx)
   DataType$Name/COUNTER   (.getLong x idx)
   DataType$Name/CUSTOM    (.getBytesUnsafe x idx)
   DataType$Name/DECIMAL   (.getDecimal x idx)
   DataType$Name/DOUBLE    (.getDouble x idx)
   DataType$Name/FLOAT     (.getFloat x idx)
   DataType$Name/INET      (.getInet x idx)
   DataType$Name/INT       (.getInt x idx)
   DataType$Name/TEXT      (.getString x idx)
   DataType$Name/TIMESTAMP (.getDate x idx)
   DataType$Name/TIMEUUID  (.getUUID x idx)
   DataType$Name/UUID      (.getUUID x idx)
   DataType$Name/VARCHAR   (.getString x idx)
   DataType$Name/VARINT    (.getVarint x idx)
   DataType$Name/LIST      (into [] (.getList x idx (types-args->type (.getTypeArguments col-type) first)))
   DataType$Name/SET       (into #{} (.getSet x idx (types-args->type (.getTypeArguments col-type) first)))
   DataType$Name/MAP       (let [t (.getTypeArguments col-type)]
                             (into {} (.getMap x idx
                                               (types-args->type t first)
                                               (types-args->type t second))))
   DataType$Name/TUPLE     (let [tuple-value (.getTupleValue x idx)
                                 types (.getComponentTypes (.getType tuple-value))
                                 len (.size types)]
                             (loop [tuple []
                                    idx' 0]
                               (if (= idx' len)
                                 tuple
                                 (recur (conj tuple (decode tuple-value
                                                            idx'
                                                            (.get types idx')))
                                        (unchecked-inc-int idx')))))
   DataType$Name/UDT       (let [udt-value (.getUDTValue x idx)
                                 udt-type (.getType udt-value)
                                 udt-type-iter (.iterator udt-type)
                                 len (.size udt-type)]
                             (loop [udt {}
                                    idx' 0]
                               (if (= idx' len)
                                 udt
                                 (let [^UserType$Field type (.next udt-type-iter)]
                                   (recur (assoc udt
                                            (.getName type)
                                            (decode udt-value
                                                    idx'
                                                    (.getType type)))
                                          (unchecked-inc-int idx'))))))))

;; only used for prepared statements
(defprotocol PCodec
  (encode [x]
    "Encodes clj value into a valid cassandra value for prepared
    statements (usefull for external libs such as joda time)"))

(extend-protocol PCodec

  (Class/forName "[B")
  (encode [x] (ByteBuffer/wrap x))

  Object
  (encode [x] x)

  nil
  (encode [x] x))

(defn result-set->maps
  [^ResultSet result-set string-keys?]
  (let [key-fn (if string-keys? identity keyword)]
    (-> (map (fn [^Row row]
               (let [cdef (.getColumnDefinitions row)
                     len (.size cdef)]
                 (loop [idx (int 0)
                        row-map (transient {})]
                   (if (= idx len)
                     (persistent! row-map)
                     (recur (unchecked-inc-int idx)
                            (assoc! row-map
                                    (key-fn (.getName cdef idx))
                                    (decode row idx (.getType cdef idx))))))))
             result-set)
        (vary-meta assoc :execution-info (.getExecutionInfo result-set)))))
