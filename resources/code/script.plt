set xrange[0:27]; set yrange[0:27]; set cbrange [-1:4]; set size ratio 1; unset xtics; unset ytics; set pm3d map; set terminal pngcairo
set output '0.png'
splot '0' matrix
