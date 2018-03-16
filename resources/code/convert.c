#include <stdio.h>
int main() {
  FILE *fp, *fpout, *fpscript;
  int row, column, iteration;
  char file_name[12] ; 
  float value;
  
  fp = fopen("input.txt", "r");

  for (iteration = 0; iteration < 49; iteration++) {
    sprintf(file_name, "%d", iteration);
    fpout = fopen(file_name, "w");
    for (row = 0; row < 32; row++) {
      for (column = 0; column < 32; column++) {
	fscanf(fp,"%f",&value);
	fprintf(fpout, "%f ", value);
      }
      fprintf(fpout, "\n");
    }
    fclose(fpout);
  }

  fpscript = fopen("script.plt", "w");
  //fprintf(fpscript, "set xrange[0:31]; set yrange[0:31]; set size ratio 1; unset xtics; unset ytics; set pm3d map; set terminal pngcairo\n");
  fprintf(fpscript, "set xrange[0:31]; set yrange[0:31]; set size ratio 1; unset xtics; unset ytics; set pm3d map; set terminal pngcairo\n");
  for (iteration = 0; iteration < 49; iteration++) {
    fprintf(fpscript, "set output '%d.png'\nsplot '%d' matrix\n", iteration, iteration);
  }
  
  fclose(fpscript);
  fclose(fp);
}
