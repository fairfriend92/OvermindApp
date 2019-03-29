#include <stdio.h>
int main() {
  FILE *fp, *fpout, *fpscript;
  int row, column, i, iterations = 0, highBound = 0, lowBound;
  char file_name[12] ; 
  float value;
  
  fp = fopen("input.txt", "r");

  printf("Number of iterations?\n");
  scanf("%d", &iterations);

  printf("Values higher bound?\n");
  scanf("%d", &highBound);

  printf("Values lower bound?\n");
  scanf("%d", &lowBound);
  
  for (i = 0; i < iterations; i++) {
    sprintf(file_name, "%d", i);
    fpout = fopen(file_name, "w");
    for (row = 0; row < 28; row++) {
      for (column = 0; column < 28; column++) {
	fscanf(fp,"%f",&value);
	fprintf(fpout, "%f ", value);
      }
      fprintf(fpout, "\n");
    }
    fclose(fpout);
  }

  fpscript = fopen("script.plt", "w");
  
  fprintf(fpscript, "set xrange[0:27]; set yrange[0:27]; set cbrange [%d:%d]; set size ratio 1; unset xtics; unset ytics; set pm3d map; set terminal pngcairo\n", lowBound, highBound);
  
  for (i = 0; i < iterations; i++) {
    fprintf(fpscript, "set output '%d.png'\nsplot '%d' matrix\n", i, i);
  }
  
  fclose(fpscript);
  fclose(fp);
}
