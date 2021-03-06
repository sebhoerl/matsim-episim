#!/bin/bash --login
#$ -l h_rt=86400
#$ -o ./logfile_$JOB_NAME.log
#$ -j y
#$ -m a
#$ -M mueller@vsp.tu-berlin.de
#$ -cwd
#$ -pe mp 4
#$ -l mem_free=4G

date
hostname

classpath="matsim-episim-1.0-SNAPSHOT-jar-with-dependencies.jar"

echo "***"
echo "classpath: $classpath"
echo "***"

# main
main="org.matsim.run.RunFromConfig"

# arguments
arguments="config_$JOB_NAME.xml --config:controler.runId $JOB_NAME"

command="java -cp $classpath @jvm.options $main $arguments"

echo ""
echo "command is $command"

echo ""
echo "using alternative java"
module add java/11
java -version

$command