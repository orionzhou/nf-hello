package nextflow.hello

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.fusion.FusionHelper
import nextflow.processor.TaskRun

import nextflow.executor.AbstractGridExecutor
import nextflow.util.ServiceName
import org.pf4j.ExtensionPoint
/**
 * Execute a task script by running it on the SGE/OGE cluster
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
@ServiceName('sge1')
class Sge1Executor extends AbstractGridExecutor implements ExtensionPoint {

    /**
     * Gets the directives to submit the specified task to the cluster for execution
     *
     * @param task A {@link TaskRun} to be submitted
     * @param result The {@link List} instance to which add the job directives
     * @return A {@link List} containing all directive tokens and values.
     */
    protected List<String> getDirectives(TaskRun task, List<String> result) {

        result << '-N' << getJobNameFor(task)
        result << '-o' << quote(task.workDir.resolve(TaskRun.CMD_LOG))
        result << '-j' << 'y'
        //result << '-terse' << ''    // note: directive need to be returned as pairs

        /*
         * By using command line option -notify SIGUSR1 will be sent to your script prior to SIGSTOP
         * and SIGUSR2 will be sent to your script prior to SIGKILL
         */
        //result << '-notify' << ''

        // the requested queue name
        if( task.config.queue ) {
            result << '-q' << (task.config.queue as String)
        }

        //number of cpus for multiprocessing/multi-threading
        if ( task.config.penv ) {
            result << "-pe" << "${task.config.penv} ${task.config.getCpus()}".toString()
        }
        //else if( task.config.getCpus()>1 ) {
        else {
            //result << "-l" << "slots=${task.config.getCpus()}".toString()
            result << "-l" << "cpu=${task.config.getCpus()}".toString()
        }

        // max task duration
        if( task.config.getTime() ) {
            final time = task.config.getTime()
            //result << "-l" << "h_rt=${time.format('HH:mm:ss')}".toString()
            result << "-l" << "walltime=${time.format('HH:mm:ss')}".toString()
        }

        // task max memory
        if( task.config.getMemory() ) {
            //final mem = "${task.config.getMemory().mega}M".toString()
            //result << "-l" << "h_rss=$mem,mem_free=$mem".toString()
            final mem = "${task.config.getMemory().giga}G".toString()
            result << "-l" << "mem=${mem}".toString()
        }

        result << '-V' << ''
        // -- at the end append the command script wrapped file name
        //if( task.config.getClusterOptions() ) {
        //    result << task.config.getClusterOptions() << ''
        //}

        return result
    }

    /*
     * Prepare the 'qsub' cmdline
     */
    List<String> getSubmitCommandLine(TaskRun task, Path scriptFile ) {
        // The '-terse' command line control the output of the qsub command line, when
        // used it only return the ID of the submitted job.
        // NOTE: In some SGE implementations the '-terse' only works on the qsub command line
        // and it is ignored when used in the script job as directive, fir this reason it
        // should not be remove from here
        scriptFile.setPermissions(7,5,5)
        return pipeLauncherScript()
                ? List.of('qsub', '-')
                : List.of('qsub', '-terse', scriptFile.name)
    }

    protected String getHeaderToken() { '#$' }


    /**
     * Parse the string returned by the {@code qsub} command and extract the job ID string
     *
     * @param text The string returned when submitting the job
     * @return The actual job ID string
     */
    @Override
    def parseJobId( String text ) {
        // return always the last line
        String id
        def lines = text.trim().readLines()
        def entry = lines[-1].trim()
        if( entry ) {
            if( entry.toString().isLong() )
                return entry

            if( entry.startsWith('Your job') && entry.endsWith('has been submitted') && (id=entry.tokenize().get(2)) )
                return id
        }

        throw new IllegalStateException("Invalid SGE submit response:\n$text\n\n")
    }

    @Override
    protected List<String> getKillCommand() { ['qdel'] }

    @Override
    protected List<String> queueStatusCommand(Object queue) {
        def result = ['qstat']
        //if( queue )
            //result << '-q' << queue.toString()

        return result
    }

    static protected Map<String,QueueStatus> DECODE_STATUS = [
        't': QueueStatus.RUNNING,
        'r': QueueStatus.RUNNING,
        'R': QueueStatus.RUNNING,
        'hr': QueueStatus.RUNNING,
        'qw': QueueStatus.PENDING,
        'h': QueueStatus.PENDING,
        'w': QueueStatus.PENDING,
        'P': QueueStatus.PENDING,
        'N': QueueStatus.PENDING,
        'S': QueueStatus.HOLD,
        's': QueueStatus.HOLD,
        'T': QueueStatus.HOLD,
        'Tr': QueueStatus.HOLD,
        'hqw': QueueStatus.HOLD,
        'Eqw': QueueStatus.ERROR,
        'E': QueueStatus.ERROR
    ]

    @Override
    protected Map<String, QueueStatus> parseQueueStatus(String text) {

        final result = new LinkedHashMap<String, QueueStatus>()
        text?.eachLine{ String row, int index ->
            if( index< 2 ) return
            def cols = row.trim().split(/\s+/)
            if( cols.size()>5 ) {
                result.put( cols[0], DECODE_STATUS[cols[4]] )
            }
        }

        return result
    }

    @Override
    String quote(Path path) {
        // note: SGE does not recognize `\` escape character in the
        // in the path defined as `#$` directives
        // just double-quote paths containing blanks
        def str = path.toString()
        str.indexOf(' ') != -1 ? "\"$str\"" : str
    }

    @Override
    protected boolean pipeLauncherScript() {
        return isFusionEnabled()
    }

    @Override
    boolean isFusionEnabled() {
        return FusionHelper.isFusionEnabled(session)
    }
}
