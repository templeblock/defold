(ns dynamo.node
  "This namespace has two fundamental jobs. First, it provides the operations
for defining and instantiating nodes: `defnode` and `construct`.

Second, this namespace defines some of the basic node types and mixins."
  (:require [clojure.set :as set]
            [clojure.core.match :refer [match]]
            [clojure.tools.macro :refer [name-with-attributes]]
            [plumbing.core :refer [defnk]]
            [schema.core :as s]
            [dynamo.property :as dp :refer [defproperty]]
            [dynamo.system :as ds]
            [dynamo.types :as t :refer :all]
            [dynamo.util :refer :all]
            [internal.node :as in]
            [internal.property :as ip]
            [internal.graph.dgraph :as dg]
            [internal.graph.lgraph :as lg]))

(set! *warn-on-reflection* true)

(defn get-node-value
  "Pull a value from a node's output, identified by `label`.
The value may be cached or it may be computed on demand. This
is transparent to the caller.

This uses the current \"world\" value of the node and its output.
That means the caller will receive a value consistent with the most
recently committed transaction.

The label must exist as a defined transform on the node, or an
AssertionError will result."
  [node label]
  (in/get-node-value node label))

(defnk selfie
  "Passthrough for self. Needed only for dependency tracking."
  [this] this)

(defn- gather-property [this prop]
  {:node-id (:_id this)
   :value (get this prop)
   :type  (-> this t/properties prop)})

(defnk gather-properties :- Properties
  "Production function that delivers the definition and value
for all properties of this node."
  [this]
  (let [property-names (-> this t/properties keys)]
    (zipmap property-names (map (partial gather-property this) property-names))))

(def node-intrinsics
  [(list 'output 'self `s/Any `selfie)
   (list 'output 'properties `Properties `gather-properties)])

(defn construct
  "Creates an instance of a node. The node-type must have been
previously defined via `defnode`.

The node's properties will all have their default values. The caller
may pass key/value pairs to override properties.

A node that has been constructed is not connected to anything and it
doesn't exist in the graph yet. The caller must use `dynamo.system/add`
to place the node in the graph.

Example:
  (defnode GravityModifier
    (property acceleration s/Int (default 32))

  (construct GravityModifier :acceleration 16)"
  [node-type & {:as args}]
  ((::ctor node-type) (merge {:_id (in/tempid)} args)))

(defmacro defnode
    "Given a name and a specification of behaviors, creates a node,
and attendant functions.

Allowed clauses are:

(inherits  _symbol_)
Compose the behavior from the named node type

(input    _symbol_ _schema_)
Define an input with the name, whose values must match the schema.

(property _symbol_ _property-type_ & _options_)
Define a property with schema and, possibly, default value and constraints.
Property type and options have the same syntax as for `dynamo.property/defproperty`.

(output   _symbol_ _type_ (:cached)? (:on-update)? _producer_)
Define an output to produce values of type. Flags ':cached' and
':on-update' are optional. _producer_ may be a var that names an fn, or fnk.
It may also be a function tail as [arglist] + forms.

Values produced on an output with the :cached flag will be cached in memory until
the node is affected by some change in inputs or properties. At that time, the
cached value will be sent for disposal.

Ordinarily, an output value is not produced until it is requested. However, an
output with the :on-update flag will be updated as soon as possible after the
previous value is invalidated.

Example (from [[editors.atlas]]):

    (defnode TextureCompiler
      (input    textureset TextureSet)
      (property texture-filename s/Str (default \"\"))
      (output   texturec s/Any compile-texturec)))

    (defnode TextureSetCompiler
      (input    textureset TextureSet)
      (property textureset-filename s/Str (default \"\"))
      (output   texturesetc s/Any compile-texturesetc)))

    (defnode AtlasCompiler
      (inherit TextureCompiler)
      (inherit TextureSetCompiler))

This will produce a record `AtlasCompiler`. `defnode` merges the behaviors appropriately.

Every node can receive messages. The node declares message handlers with a special syntax:

(on _message_type_ _form_)

The form will be evaluated inside a transactional body. This means that it can
use the special clauses to create nodes, change connections, update properties, and so on.

A node definition allows any number of 'on' clauses.

A node may also implement protocols or interfaces, using a syntax identical
to `deftype` or `defrecord`. A node may implement any number of such protocols.

Every node always implements dynamo.types/Node.

If there are any event handlers defined for the node type, then it will also
implement dynamo.types/MessageTarget."
  [symb & body]
  (let [[symb forms] (name-with-attributes symb body)
        record-name  (in/classname-for symb)
        ctor-name    (symbol (str 'map-> record-name))]
    `(do
       (declare ~ctor-name ~symb)
       (let [description# ~(in/node-type-sexps (concat node-intrinsics forms))
             ctor#        (fn [args#] (~ctor-name (merge (in/defaults ~symb) args#)))]
         (def ~symb (in/make-node-type (assoc description# ::ctor ctor#)))
         (in/define-node-record  '~record-name '~symb ~symb)
         (in/define-print-method '~record-name '~symb ~symb)
         (var ~symb)))))

(defn abort
  "Abort production function and use substitute value."
  [msg & args]
  (throw (apply ex-info msg args)))

(defn dispatch-message
  "This is an advanced usage. If you have a reference to a node, you can directly send
it a message.

This function should mainly be used to create 'plumbing'."
  [node type & {:as body}]
  (process-one-event (ds/refresh node) (assoc body :type type)))


(defn get-node-inputs
  "Sometimes a production function needs direct access to an input source.
   This function takes any number of input labels and returns a vector of their
   values, in the same order."
  [node & labels]
  (map (in/collect-inputs node (-> node :world-ref deref :graph) (zipmap labels (repeat :ok))) labels))

; ---------------------------------------------------------------------------
; Bootstrapping the core node types
; ---------------------------------------------------------------------------
(defn inject-new-nodes
  "Implementation function that performs dependency injection for nodes.
This function should not be called directly."
  [graph self transaction]
  (let [existing-nodes           (cons self (in/get-inputs self graph :nodes))
        nodes-added              (map #(dg/node graph %) (:nodes-added transaction))
        out-from-new-connections (in/injection-candidates existing-nodes nodes-added)
        in-to-new-connections    (in/injection-candidates nodes-added existing-nodes)
        candidates               (set/union out-from-new-connections in-to-new-connections)
        candidates               (remove (fn [[out out-label in in-label]]
                                           (or
                                             (= (:_id out) (:_id in))
                                             (lg/connected? graph (:_id out) out-label (:_id in) in-label)))
                                   candidates)]
    (doseq [connection candidates]
      (apply ds/connect connection))))

(defn dispose-nodes
  "Trigger to dispose nodes from a scope when the scope is destroyed.
This should not be called directly."
  [graph self transaction]
  (when (ds/is-removed? transaction self)
    (let [graph-before-deletion (-> transaction :world-ref deref :graph)
          nodes-to-delete       (:nodes (in/collect-inputs self graph-before-deletion {:nodes :ok}))]
      (doseq [n nodes-to-delete]
        (ds/delete n)))))

(defproperty Triggers Callbacks (visible false))

(defnode Scope
  "Scope provides a level of grouping for nodes. Scopes nest.
When a node is added to a Scope, the node's :self output will be
connected to the Scope's :nodes input.

When a Scope is deleted, all nodes within that scope will also be deleted."
  (input nodes [s/Any])

  (property tag      s/Keyword)
  (property parent   (s/protocol NamingContext))
  (property triggers Triggers (visible false) (default [#'inject-new-nodes #'dispose-nodes]))

  (output dictionary s/Any in/scope-dictionary)

  NamingContext
  (lookup [this nm] (-> (get-node-value this :dictionary) (get nm))))

(defnode RootScope
  "There should be exactly one RootScope in the graph, with ID 1.
RootScope has no parent."
  (inherits Scope)
  (property tag s/Keyword (default :root)))

(defmethod print-method RootScope__
  [^RootScope__ v ^java.io.Writer w]
  (.write w (str "<RootScope{:_id " (:_id v) "}>")))

(defn mark-dirty
  "Trigger used to implement dirty tracking for content nodes.
Do not call this directly."
  [graph self transaction]
  (when (and (ds/is-modified? transaction self) (not (ds/is-added? transaction self)))
    (ds/set-property self :dirty true)))

(defnode DirtyTracking
  "Mixin. Content root nodes (i.e., top level nodes for an editor tab) can inherit
this node to set a dirty flag whenever the node is altered by a transaction."
  (property triggers Triggers (default [#'mark-dirty]))
  (property dirty s/Bool
    (default false)
    (visible false)))

(defnode Saveable
  "Mixin. Content root nodes (i.e., top level nodes for an editor tab) can inherit
this node to indicate that 'Save' is a meaningful action.

Inheritors are required to supply a production function for the :save output."
  (output save s/Keyword :abstract))


