(ns puppetlabs.services.file-serving.utils.posix-utils
  (:import [java.nio.file.attribute PosixFilePermission]
           [java.nio.file.attribute PosixFileAttributes]
           [java.nio.file Path]))

(def OWNER_READ_MODE 0400)
(def OWNER_WRITE_MODE 0200)
(def OWNER_EXEC_MODE 0100)
(def GROUP_READ_MODE 0040)
(def GROUP_WRITE_MODE 0020)
(def GROUP_EXEC_MODE 0010)
(def OTHERS_READ_MODE 0004)
(def OTHERS_WRITE_MODE 0002)
(def OTHERS_EXEC_MODE 0001)

(defn- bit->mode
  [acc bit]
  (cond
    (= bit PosixFilePermission/OWNER_READ) (bit-or acc OWNER_READ_MODE)
    (= bit PosixFilePermission/OWNER_WRITE) (bit-or acc OWNER_WRITE_MODE)
    (= bit PosixFilePermission/OWNER_EXECUTE) (bit-or acc OWNER_EXEC_MODE)
    (= bit PosixFilePermission/GROUP_READ) (bit-or acc GROUP_READ_MODE)
    (= bit PosixFilePermission/GROUP_WRITE) (bit-or acc GROUP_WRITE_MODE)
    (= bit PosixFilePermission/GROUP_EXECUTE) (bit-or acc GROUP_EXEC_MODE)
    (= bit PosixFilePermission/OTHERS_READ) (bit-or acc OTHERS_READ_MODE)
    (= bit PosixFilePermission/OTHERS_WRITE) (bit-or acc OTHERS_WRITE_MODE)
    (= bit PosixFilePermission/OTHERS_EXECUTE) (bit-or acc OTHERS_EXEC_MODE)))

(defn permissions->mode
  [permissions]
  "Transforms a java set of PosixFilePermission to an octal representation of the file permissions"
  (reduce bit->mode 0 permissions))

(defn owner
  [attributes]
  "Returns the owner name from the given attributes"
  {:pre [(instance? PosixFileAttributes attributes)]}
  (.get attributes "uid"))

(defn group
  [attributes]
  "Returns the group name from the given attributes"
  {:pre [(instance? PosixFileAttributes attributes)]}
  (.get attributes "gid"))

(defn mode
  [attributes]
  "Returns the mode as from the given attributes and return the mode as an octal number"
  {:pre [(instance? PosixFileAttributes attributes)]}
  (-> attributes
      (.get "permissions")
      (permissions->mode)))

(defn unix-attributes
  [file links]
  "returns a map of java 7 Nio Posix Attributes for a given file"
  {:pre [(instance? Path file)
         (or (= "manage" links)
             (= "follow" links))]}
  (let [link-options (if (= links "manage")
                       (into-array java.nio.file.LinkOption [java.nio.file.LinkOption/NOFOLLOW_LINKS])
                       (into-array java.nio.file.LinkOption []))]
    (into {} (java.nio.file.Files/readAttributes file "unix:size,uid,gid,isDirectory,isRegularFile,isSymbolicLink,permissions,creationTime,lastModifiedTime,lastAccessTime" link-options))))

(defn resolve-link
  [link]
  "returns the file Path pointed by the Path link or throws an exception"
  {:pre [(instance? Path link)]}
  (.toRealPath link (into-array java.nio.file.LinkOption [])))