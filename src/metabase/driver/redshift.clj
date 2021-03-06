(ns metabase.driver.redshift
  "Amazon Redshift Driver."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [honeysql.core :as hsql]
            [metabase
             [config :as config]
             [driver :as driver]
             [util :as u]]
            [metabase.driver
             [generic-sql :as sql]
             [postgres :as postgres]]
            [metabase.util
             [honeysql-extensions :as hx]
             [ssh :as ssh]]))

(defn- connection-details->spec
  "Create a database specification for a redshift database. Opts should include
  keys for :db, :user, and :password. You can also optionally set host and
  port."
  [{:keys [host port db],
    :as opts}]
  (merge {:classname "com.amazon.redshift.jdbc.Driver" ; must be in classpath
          :subprotocol "redshift"
          :subname (str "//" host ":" port "/" db "?OpenSourceSubProtocolOverride=false")
          :ssl true}
         (dissoc opts :host :port :db)))

(defn- date-interval [unit amount]
  (hsql/call :+ :%getdate (hsql/raw (format "INTERVAL '%d %s'" (int amount) (name unit)))))

(defn- unix-timestamp->timestamp [expr seconds-or-milliseconds]
  (case seconds-or-milliseconds
    :seconds      (hx/+ (hsql/raw "TIMESTAMP '1970-01-01T00:00:00Z'")
                        (hx/* expr
                              (hsql/raw "INTERVAL '1 second'")))
    :milliseconds (recur (hx// expr 1000) :seconds)))

(defn- describe-database
  "Custom implementation of `describe-database` for Redshift."
  [database]
  {:tables (set (for [fk (jdbc/query (sql/db->jdbc-connection-spec database)
                              ["SELECT DISTINCT table_name,table_schema
                                      FROM svv_columns
                                      WHERE table_schema NOT IN ('pg_internal','pg_catalog','information_schema');"])]
           {
            :name     (:table_name fk)
            :schema   (:table_schema fk)
           }))})

(defn- column->base-type
  "Mappings for Redshift type goes here
   Same as Postgresql + types for redshift spectrum as well
   TODO: Reduce to https://docs.aws.amazon.com/redshift/latest/dg/c_Supported_data_types.html ?"
  [column-type]
  (
  {:bigint        :type/BigInteger
   :bigserial     :type/BigInteger
   :bit           :type/*
   :bool          :type/Boolean
   :boolean       :type/Boolean
   :box           :type/*
   :bpchar        :type/Text ; "blank-padded char" is the internal name of "character"
   :bytea         :type/*    ; byte array
   :cidr          :type/Text ; IPv4/IPv6 network address
   :circle        :type/*
   :citext        :type/Text ; case-insensitive text
   :date          :type/Date
   :decimal       :type/Decimal
   :double        :type/Decimal        ; Redshift Spectrum
   :float         :type/Float
   :float4        :type/Float
   :float8        :type/Float
   :geometry      :type/*
   :inet          :type/IPAddress
   :int           :type/Integer
   :integer       :type/Integer         ; Redshift Spectrum
   :int2          :type/Integer
   :int4          :type/Integer
   :int8          :type/BigInteger
   :interval      :type/*               ; time span
   :json          :type/Text
   :jsonb         :type/Text
   :line          :type/*
   :lseg          :type/*
   :macaddr       :type/Text
   :money         :type/Decimal
   :nvarchar      :type/Text
   :numeric       :type/Decimal
   :path          :type/*
   :pg_lsn        :type/Integer         ; PG Log Sequence #
   :point         :type/*
   :real          :type/Float
   :serial        :type/Integer
   :serial2       :type/Integer
   :serial4       :type/Integer
   :serial8       :type/BigInteger
   :smallint      :type/Integer
   :smallserial   :type/Integer
   :string        :type/Text           ; Redshift Spectrum
   :text          :type/Text
   :time          :type/Time
   :timetz        :type/Time
   :timestamp     :type/DateTime
   :timestamptz   :type/DateTime
   :tinyint       :type/Integer        ; Redshift Spectrum
   :tsquery       :type/*
   :tsvector      :type/*
   :txid_snapshot :type/*
   :uuid          :type/UUID
   :varbit        :type/*
   :varchar       :type/Text
   :xml           :type/Text
   (keyword "bit varying")                :type/*
   (keyword "character varying")          :type/Text
   (keyword "double precision")           :type/Float
   (keyword "time with time zone")        :type/Time
   (keyword "time without time zone")     :type/Time
   (keyword "timestamp with timezone")    :type/DateTime
   (keyword "timestamp without timezone") :type/DateTime}  (keyword (clojure.string/replace (name column-type) #"\([0-9]+\)" "")))) ;; Removing (number) for varchar

;; The Postgres JDBC .getImportedKeys method doesn't work for Redshift, and we're not allowed to access
;; information_schema.constraint_column_usage, so we'll have to use this custom query instead
;;
;; See also: [Related Postgres JDBC driver issue on GitHub](https://github.com/pgjdbc/pgjdbc/issues/79)
;;           [How to access the equivalent of information_schema.constraint_column_usage in Redshift](https://forums.aws.amazon.com/thread.jspa?threadID=133514)
(defn- describe-table-fks [database table]
  (set (for [fk (jdbc/query (sql/db->jdbc-connection-spec database)
                            ["SELECT source_column.attname AS \"fk-column-name\",
                                     dest_table.relname    AS \"dest-table-name\",
                                     dest_table_ns.nspname AS \"dest-table-schema\",
                                     dest_column.attname   AS \"dest-column-name\"
                              FROM pg_constraint c
                                     JOIN pg_namespace n             ON c.connamespace          = n.oid
                                     JOIN pg_class source_table      ON c.conrelid              = source_table.oid
                                     JOIN pg_attribute source_column ON c.conrelid              = source_column.attrelid
                                     JOIN pg_class dest_table        ON c.confrelid             = dest_table.oid
                                     JOIN pg_namespace dest_table_ns ON dest_table.relnamespace = dest_table_ns.oid
                                     JOIN pg_attribute dest_column   ON c.confrelid             = dest_column.attrelid
                              WHERE c.contype                 = 'f'::char
                                     AND source_table.relname = ?
                                     AND n.nspname            = ?
                                     AND source_column.attnum = ANY(c.conkey)
                                     AND dest_column.attnum   = ANY(c.confkey)"
                             (:name table)
                             (:schema table)])]
         {:fk-column-name   (:fk-column-name fk)
          :dest-table       {:name   (:dest-table-name fk)
                             :schema (:dest-table-schema fk)}
          :dest-column-name (:dest-column-name fk)})))

(defrecord RedshiftDriver []
  :load-ns true
  clojure.lang.Named
  (getName [_] "Amazon Redshift"))

;; The docs say TZ should be allowed at the end of the format string, but it doesn't appear to work
;; Redshift is always in UTC and doesn't return it's timezone
(def ^:private redshift-date-formatters (driver/create-db-time-formatters "yyyy-MM-dd HH:mm:ss.SSS zzz"))
(def ^:private redshift-db-time-query "select to_char(current_timestamp, 'YYYY-MM-DD HH24:MI:SS.MS TZ')")

(u/strict-extend RedshiftDriver
  driver/IDriver
  (merge (sql/IDriverSQLDefaultsMixin)
         {:date-interval            (u/drop-first-arg date-interval)
          :describe-database        (u/drop-first-arg describe-database)
          :describe-table-fks       (u/drop-first-arg describe-table-fks)
          :details-fields           (constantly (ssh/with-tunnel-config
                                                  [{:name         "host"
                                                    :display-name "Host"
                                                    :placeholder  "my-cluster-name.abcd1234.us-east-1.redshift.amazonaws.com"
                                                    :required     true}
                                                   {:name         "port"
                                                    :display-name "Port"
                                                    :type         :integer
                                                    :default      5439}
                                                   {:name         "db"
                                                    :display-name "Database name"
                                                    :placeholder  "toucan_sightings"
                                                    :required     true}
                                                   {:name         "user"
                                                    :display-name "Database username"
                                                    :placeholder  "cam"
                                                    :required     true}
                                                   {:name         "password"
                                                    :display-name "Database user password"
                                                    :type         :password
                                                    :placeholder  "*******"
                                                    :required     true}]))
          :format-custom-field-name (u/drop-first-arg str/lower-case)
          :current-db-time          (driver/make-current-db-time-fn redshift-db-time-query redshift-date-formatters)})

  sql/ISQLDriver
  (merge postgres/PostgresISQLDriverMixin
         {:connection-details->spec  (u/drop-first-arg connection-details->spec)
          :current-datetime-fn       (constantly :%getdate)
          :column->base-type         (u/drop-first-arg column->base-type)
          :set-timezone-sql          (constantly "SET TIMEZONE TO %s;")
          :unix-timestamp->timestamp (u/drop-first-arg unix-timestamp->timestamp)}
         ;; HACK ! When we test against Redshift we use a session-unique schema so we can run simultaneous tests
         ;; against a single remote host; when running tests tell the sync process to ignore all the other schemas
         (when config/is-test?
           {:excluded-schemas (memoize
                               (fn [_]
                                 (require 'metabase.test.data.redshift)
                                 (let [session-schema-number @(resolve 'metabase.test.data.redshift/session-schema-number)]
                                   (set (conj (for [i (range 240)
                                                    :when (not= i session-schema-number)]
                                                (str "schema_" i))
                                              "public")))))})))

(defn -init-driver
  "Register the Redshift driver"
  []
  (driver/register-driver! :redshift (RedshiftDriver.)))
