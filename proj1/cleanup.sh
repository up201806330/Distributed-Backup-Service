#! /usr/bin/bash

# Placeholder for clean-up script
# To be executed in the root of the build tree
# Requires at most one argument: the peer id
# Cleans the directory tree for storing 
#  both the chunks and the restored files of
#  either a single peer, in which case you may or not use the argument
#    or for all peers, in which case you 


# Check number input arguments
argc=$#

if ((argc == 1 ))
then
	peer_id=$1
else 
	echo "Usage: $0 [<peer_id>]]"
	exit 1
fi

rm -rf build/service-${peer_id}
