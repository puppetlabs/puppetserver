(ns puppetlabs.enterprise.services.protocols.scheduler)

(defprotocol SchedulerService

  (interspaced [this n f]
    "Calls 'f' repeatedly with a delay of 'n' milliseconds between the
    completion of a given invocation and the beginning of the following
    invocation.  Returns an identifier for the scheduled job.")

  (after [this n f]
    "Calls 'f' once after a delay of 'n' milliseconds.
    Returns an identifier for the scheduled job.")

  (stop-job [this id]
    "Given an identifier of a scheduled job, stop its execution.  If an
    invocation of the job is currently executing, it will be allowed to
    complete,  but the job will not be invocated again.
    Returns 'true' if the job was successfully stopped, 'false' otherwise."))
