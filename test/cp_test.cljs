;; test/cp_test.cljs -- build the command and compare it with the system cp
;; on stdout, stderr, exit status AND the resulting directory tree.
;;
;; A copy tool that printed nothing and exited 0 would pass an output-only
;; comparison completely, so the tree is what carries the weight here: after
;; each case both implementations' directories are hashed file by file and
;; compared.
;;
;; Both implementations run in the SAME directory, one after the other, with
;; the fixtures rebuilt in between. That is not tidiness -- the diagnostics
;; contain absolute paths, so two parallel trees would differ in stderr for
;; reasons that have nothing to do with the behaviour under test.
;;
;; Part B asserts the two KNOWN divergences still diverge exactly as the
;; README describes them. A divergence that stops being true is as much a
;; finding as one that appears.

(ns cp-test
  (:require [clojure.string :as str] ["fs" :as fs] ["path" :as path] ["os" :as os]
            ["crypto" :as crypto]))

(def cp-mod (js/require "node:child_process"))

(defn- run [cmd args opts]
  (let [r (.spawnSync cp-mod cmd (clj->js args)
                      (clj->js (merge {:encoding "buffer"} opts)))]
    {:status (.-status r) :signal (.-signal r)
     :out (.-stdout r) :err (.-stderr r)}))

(defn- refuse [message]
  (println (pr-str {:ok false :phase :setup :message message}))
  (.exit js/process 2))

(def amu-home
  (or (.-AMU_HOME js/process.env)
      (let [guess (.resolve path (.cwd js/process) ".." ".." "kotoba-lang" "amu")]
        (when (.existsSync fs (.join path guess "bin" "amu")) guess))))

(def system-cp "/bin/cp")

;; The fixture tree, rebuilt before every single run.
;;   a.txt      ordinary content
;;   pre.txt    ALREADY EXISTS at mode 600 -- the overwrite case, where both
;;              implementations must leave the mode alone
;;   exec.sh    mode 755 -- the source whose mode a new destination should
;;              inherit and (measurably) does not
;;   utf8.txt   multi-byte content
;;   empty.txt  zero bytes: reads as the empty string, which must not be
;;              confused with "could not read"
;;   bin.dat    NOT valid UTF-8 -- part C
;;   dir/       a directory, as both a source (refused) and a destination
;;              (copied into)
(def files
  {"a.txt"     "hello\n"
   "pre.txt"   "old\n"
   "exec.sh"   "#!/bin/sh\necho hi\n"
   "utf8.txt"  "日本語\n"
   "empty.txt" ""})

(defn- reset! [data]
  (.rmSync fs data #js {:recursive true :force true})
  (.mkdirSync fs data #js {:recursive true})
  (doseq [[n c] files] (.writeFileSync fs (.join path data n) c "utf8"))
  (.chmodSync fs (.join path data "pre.txt") 0x180)   ;; 0600
  (.chmodSync fs (.join path data "exec.sh") 0x1ed)   ;; 0755
  (.writeFileSync fs (.join path data "bin.dat")
                  (.from js/Buffer #js [0xff 0xfe 0x00 0x01 0x62 0x61 0x64]))
  (.mkdirSync fs (.join path data "dir")))

;; relative path -> sha256 of contents, for every file under the tree.
(defn- snapshot [root]
  (letfn [(walk [dir prefix acc]
            (reduce (fn [a e]
                      (let [full (.join path dir e)
                            rel (if (= prefix "") e (str prefix "/" e))]
                        (if (.isDirectory (.statSync fs full))
                          (walk full rel (assoc a (str rel "/") "<dir>"))
                          (assoc a rel (-> (.createHash crypto "sha256")
                                           (.update (.readFileSync fs full))
                                           (.digest "hex"))))))
                    acc
                    (sort (.readdirSync fs dir))))]
    (walk root "" {})))

(defn- mode-of [p] (bit-and (.-mode (.statSync fs p)) 0x1ff))

;; Each case is an argv of names relative to the data directory; anything
;; that is not a bare name is passed through untouched.
(def cases
  [;; the basic contract: a new destination
   ["a.txt" "new.txt"]
   ;; overwriting an existing destination -- mode must be left alone by both
   ["a.txt" "pre.txt"]
   ;; into a directory, under the source's own name
   ["a.txt" "dir"]
   ;; a multi-byte source
   ["utf8.txt" "new.txt"]
   ;; an EMPTY source: reads as "", which must not be taken for a failure
   ["empty.txt" "new.txt"]
   ;; copying a file onto itself is refused by cp; this asserts we agree
   ["a.txt" "a.txt"]
   ;; missing source
   ["missing.txt" "new.txt"]
   ;; the source is a directory and there is no -R
   ["dir" "new.txt"]
   ;; the destination's parent does not exist
   ["a.txt" "nodir/x.txt"]
   ;; wrong operand counts: cp exits 64 with a two-line usage, not 1
   ["a.txt"]
   []])

(when-not amu-home (refuse "set AMU_HOME to an amu checkout"))
(let [amu (.join path amu-home "bin" "amu")
      packager (.join path amu-home "scripts" "package-command.cljs")]
  (when-not (.existsSync fs amu) (refuse (str "no amu at " amu)))
  (when-not (.existsSync fs packager) (refuse (str "no packager at " packager)))
  (when-not (.existsSync fs system-cp) (refuse (str "no " system-cp " to compare against")))
  (let [tmp (.mkdtempSync fs (.join path (.tmpdir os) "org-ieee-cp-"))
        src (.resolve path (.cwd js/process) "cp" "core.kotoba")
        policy (.join path tmp "policy.edn")
        kexe (.join path tmp "cp.kexe")
        blob (.join path tmp "cp.bin")
        exe (.join path tmp "cp")
        data (.join path tmp "data")]
    (.writeFileSync fs policy
                    "{:allow #{[:cap/call 34] [:cap/call 35] [:cap/call 38] [:cap/call 39]}}" "utf8")
    (.mkdirSync fs data)
    (let [c (run "node" [amu "compile" src "--target" "aarch64-macos" "--jvm-free"
                         "--policy" policy "--output" kexe] {})]
      (when (not= 0 (:status c))
        (refuse (str "compile failed: " (str (:err c)) (str (:out c))))))
    (let [e (run "node" [amu "extract-native" kexe "--symbol" "main" "--output" blob] {})
          _ (when (not= 0 (:status e)) (refuse (str "extract failed: " (str (:err e)))))
          report (str (:out e))
          offset (second (re-find #":offset (\d+)" report))]
      (when-not offset (refuse (str "no :offset in the extract report: " report)))
      (let [real (.realpathSync fs data)
            p (run "nbb" [packager "--code" blob "--offset" offset "--isa" "aarch64"
                          "--allow" "34,35,38,39"
                          "--fs-scope" real "--browse-scope" real
                          "--string-pool" "8000000" "--fuel" "50000000"
                          "--pairs" "200000" "--output" exe] {})]
        (when (not= 0 (:status p)) (refuse (str "package failed: " (str (:err p)))))))

    (let [real (.realpathSync fs data)
          abs (fn [n] (if (re-find #"^[-/]" n) n (.join path real n)))
          b64 (fn [b] (if b (.toString b "base64") ""))
          ;; Run one implementation over a freshly rebuilt tree.
          once (fn [cmd argv]
                 (reset! real)
                 (let [r (run cmd (mapv abs argv) {:cwd real})]
                   (assoc r :tree (snapshot real))))
          results
          (for [argv cases]
            (let [k (once exe argv)
                  s (once system-cp argv)
                  same? (and (= (b64 (:out k)) (b64 (:out s)))
                             (= (b64 (:err k)) (b64 (:err s)))
                             (= (:status k) (:status s))
                             (= (:tree k) (:tree s)))]
              {:argv argv :ok same?
               :exit [(:status k) (:status s)]
               :err [(.toString (:err k) "utf8") (.toString (:err s) "utf8")]
               :tree-same (= (:tree k) (:tree s))}))
          bad (remove :ok results)]
      (doseq [r results]
        (println (str (if (:ok r) "  ok   " "  FAIL ") (pr-str (:argv r))
                      " exit " (pr-str (:exit r))
                      (when-not (:ok r)
                        (str " tree-same=" (:tree-same r) " err=" (pr-str (:err r)))))))

      ;; --- Part B: the divergences the README names ----------------------
      (println "\n  -- named divergences (each must still be true) --")
      (let [real2 real
            ;; 1. a NEW destination from a 755 source
            _ (reset! real2)
            _ (run exe [(abs "exec.sh") (abs "new.sh")] {:cwd real2})
            k-mode (mode-of (.join path real2 "new.sh"))
            _ (reset! real2)
            _ (run system-cp [(abs "exec.sh") (abs "new.sh")] {:cwd real2})
            s-mode (mode-of (.join path real2 "new.sh"))
            mode-diverges (and (= s-mode 0x1ed) (= k-mode 0x1a4))
            ;; 2. three operands
            _ (reset! real2)
            k3 (run exe [(abs "a.txt") (abs "b.txt") (abs "c.txt")] {:cwd real2})
            _ (reset! real2)
            s3 (run system-cp [(abs "a.txt") (abs "b.txt") (abs "c.txt")] {:cwd real2})
            three-diverges (not= (:status k3) (:status s3))]
        (println (str (if mode-diverges "  ok   " "  FAIL ")
                      "new destination from a 755 source: kotoba "
                      (.toString k-mode 8) ", " system-cp " " (.toString s-mode 8)
                      " (expected 644 vs 755)"))
        (println (str (if three-diverges "  ok   " "  FAIL ")
                      "three operands: kotoba exits " (:status k3)
                      " (usage), " system-cp " exits " (:status s3)
                      " -- unsupported, named in the README"))

        ;; --- Part C: bytes that are not valid UTF-8 ---------------------
        (reset! real2)
        (let [kb (run exe [(abs "bin.dat") (abs "out.dat")] {:cwd real2})
              ;; Read the destination's existence HERE, while the guest's own
              ;; tree is still standing. Read after the system run below and
              ;; this measures /bin/cp's output instead -- which it did on the
              ;; first run of this suite, reporting "destination written:
              ;; true" for a guest that had written nothing.
              kb-wrote (.existsSync fs (.join path real2 "out.dat"))
              _ (reset! real2)
              sb (run system-cp [(abs "bin.dat") (abs "out.dat")] {:cwd real2})
              sb-ok (and (= 0 (:status sb))
                         (.existsSync fs (.join path real2 "out.dat")))
              ;; The guest must NOT have produced a destination: a trap, not
              ;; a truncated or mangled copy.
              kb-trapped (and (not= 0 (:status kb)) (not kb-wrote))]
          (println (str (if (and sb-ok kb-trapped) "  ok   " "  FAIL ")
                        "invalid UTF-8: " system-cp " copies it (exit " (:status sb)
                        "), kotoba refuses (exit " (pr-str (:status kb))
                        " signal " (pr-str (:signal kb))
                        ", destination written: " kb-wrote ")"))
          (let [extras (+ (if mode-diverges 0 1) (if three-diverges 0 1)
                          (if (and sb-ok kb-trapped) 0 1))]
            (println (pr-str {:ok (and (empty? bad) (zero? extras))
                              :cases (count results) :failed (count bad)
                              :divergence-checks-failed extras}))
            (.exit js/process (if (or (seq bad) (pos? extras)) 1 0))))))))
