# Keep only the native startup trip. Other D-Bus properties are never stored.
# Numeric output is deliberately small; Java validates it again before display.
/'host_trip_distance_time':/ {
 s=$0
 sub(/^.*'host_trip_distance_time': </,"",s)
 if (s !~ /^\(0, 0, \[0(\.0+)?, /) next
 sub(/^\(0, 0, \[/,"",s)
 n=split(s,tail,/\], int64 /)
 if(n!=2) next
 if(split(tail[1],v,/, /)!=3) next
 if(v[2] !~ /^[0-9]+(\.[0-9]+)?([eE][+-]?[0-9]+)?$/) next
 if(v[3] !~ /^[0-9]+(\.0+)?$/) next
 if(tail[2] !~ /^[0-9]+\)>/) next
 sub(/\)>.*/,"",tail[2])
 if(v[2]+0>99999 || v[3]+0>10080 || tail[2]+0<=0) next
 printf "OPTRIP1 %s %s %s\n",v[2],v[3],tail[2]
 fflush()
 exit
}
