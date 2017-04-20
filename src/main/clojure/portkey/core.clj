(ns portkey.core
  (:require
    [portkey.kryo :as kryo]
    [portkey.logdep :refer [log-dep *log-dep*]]))



; code to generate boiler plate
#_(for [method (.getMethods clojure.asm.MethodVisitor)
       :when (and (re-matches #"visit.*Insn" (.getName method))
               (not (#{"visitLdcInsn" "visitMethodInsn"} (.getName method))))
       :let [name (symbol (.getName method))
             args (repeatedly (.getParameterCount method) gensym)]]
   `(~name [~@args]
      (reset! ~'strs [])))

(defn inspect-class [bytes]
  (let [rdr (clojure.asm.ClassReader. bytes)
        class-visitor
        (proxy [clojure.asm.ClassVisitor] [clojure.asm.Opcodes/ASM4]
          (visitMethod [access method-name mdesc sig exs]
            (let [strs (atom [])]
              (proxy [clojure.asm.MethodVisitor] [clojure.asm.Opcodes/ASM4]
                (visitLdcInsn [x]
                  (if (string? x)
                    (swap! strs conj x)
                    (reset! strs [])))
                (visitMethodInsn [opcode owner name desc]
                  (cond
                    (and (= opcode clojure.asm.Opcodes/INVOKESTATIC)
                      (= owner "clojure/lang/RT")
                      (= name "var")
                      (= desc "(Ljava/lang/String;Ljava/lang/String;)Lclojure/lang/Var;")
                      (<= 2 (count @strs))) ; TODO warn when less than 2
                    (do
                      (log-dep :var-ref (symbol (peek (pop @strs)) (peek @strs)))
                      (reset! strs []))
                    (or (= opcode clojure.asm.Opcodes/INVOKESTATIC)
                      (and (= opcode clojure.asm.Opcodes/INVOKESPECIAL) (= "<init>" name)))
                    (log-dep :class-name owner)))
                (visitInsn [G__4852] (clojure.core/reset! strs [])) (visitIntInsn [G__4853 G__4854] (clojure.core/reset! strs [])) (visitFieldInsn [G__4855 G__4856 G__4857 G__4858] (clojure.core/reset! strs [])) (visitVarInsn [G__4859 G__4860] (clojure.core/reset! strs [])) (visitIincInsn [G__4861 G__4862] (clojure.core/reset! strs [])) (visitJumpInsn [G__4863 G__4864] (clojure.core/reset! strs [])) (visitTableSwitchInsn [G__4865 G__4866 G__4867 G__4868] (clojure.core/reset! strs [])) (visitLookupSwitchInsn [G__4869 G__4870 G__4871] (clojure.core/reset! strs [])) (visitInvokeDynamicInsn [G__4872 G__4873 G__4874 G__4875] (clojure.core/reset! strs [])) (visitTypeInsn [G__4876 G__4877] (clojure.core/reset! strs [])) (visitMultiANewArrayInsn [G__4878 G__4879] (clojure.core/reset! strs []))))))]
    (.accept rdr class-visitor 0)))

; shim for ouroboros
(require 'no.disassemble)
(defn bytecode [class]
  (println "Retrieving bytecode of" class)
  (get (@#'no.disassemble/classes) (@#'no.disassemble/sanitize (if (class? class) (.getCanonicalName class) class))))

(def default-whitelist 
  #(if (var? %)
     (some-> % meta :ns ns-name (= 'clojure.core))
     (re-matches #"(?:clojure/lang|java)/.*" %)))

(defn package 
  ([root]
    (package root bytecode default-whitelist))
  ([root bytecode whitelist?]
    (let [deps (atom [])]
      (binding [*log-dep* #(swap! deps conj %)]
        (let [root-bytes (kryo/freeze root)]
          (loop [todo (set @deps) vars {} classes #{}]
            (reset! deps [])
            (if-some [dep (first todo)]
              (let [todo (disj todo dep)]
                (cond
                  (or (whitelist? dep) (vars dep) (classes dep)) (recur todo vars classes)
                  (var? dep)
                  (let [bytes (kryo/freeze @dep)]
                    (recur (into todo @deps) (assoc vars dep bytes) classes))
                  (string? dep)
                  (do
                    (inspect-class (bytecode dep))
                    (recur (into todo @deps) vars (conj classes dep)))))
              {:vars vars :classes classes :root root-bytes})))))))

(defn bootstrap
  "Returns a serialized thunk (0-arg fn). This thunk when called returns deserialized root with all vars set."
  [{:keys [root vars]}]
  (kryo/freeze
    (fn []
     (doseq [[^clojure.lang.Var v bs] vars]
       (.bindRoot v (kryo/unfreeze bs)))
     (kryo/unfreeze root))))